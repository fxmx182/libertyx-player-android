package com.mauricio.libertyx.smb

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tamanho e data dos arquivos de uma pasta do servidor.
 *
 * Existe só porque o VLC não conta: a listagem que ele devolve traz nome e
 * tipo, e nada mais. Sem tamanho e data, a pasta de rede só poderia ser
 * ordenada por nome — as opções da biblioteca ficariam pela metade justamente
 * onde a coleção costuma ser maior.
 *
 * **Quem lista continua sendo o VLC** (ver [SmbBrowser]): este cliente não
 * decide o que aparece nem o que toca. Ele só completa dois campos, e se falhar
 * — servidor que ele não entende, autenticação que só o VLC acerta — a pasta
 * abre igual, sem tamanho e sem data. Por isso ele nunca lança: devolve `null`.
 */
object SmbMetadados {

    data class Info(val tamanho: Long, val modificadoEm: Long)

    private const val TAG = "LibertyX"

    private fun configuracao() = SmbConfig.builder()
        // O MD4 do NTLM não existe na criptografia do Android; o do Bouncy
        // Castle vem junto com a biblioteca.
        .withSecurityProvider(BCSecurityProvider())
        // Só NTLM: o Kerberos puxaria classes de `javax` que o Android não
        // tem, e servidor de casa não usa Kerberos.
        .withAuthenticators(NtlmAuthenticator.Factory())
        .withDfsEnabled(false)
        .withTimeout(6, TimeUnit.SECONDS)
        .withSoTimeout(6, TimeUnit.SECONDS)

    private val cliente by lazy { SMBClient(configuracao().build()) }

    /**
     * Para convidado, SMB 2 sem o 3.
     *
     * No SMB 3 o smbj deriva as chaves da sessão a partir da senha — e sessão
     * de convidado não tem chave, então ele cai num `NullPointerException`
     * antes de listar qualquer coisa. No 2.1 não há derivação, e para ler
     * tamanho e data não faz diferença nenhuma.
     */
    private val clienteSemSmb3 by lazy {
        SMBClient(configuracao().withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2).build())
    }

    suspend fun listar(server: SmbServer, senha: String?, share: String, pasta: String): Map<String, Info>? =
        runInterruptible(Dispatchers.IO) {
            try {
                noDisco(server, senha, share) { disco ->
                    disco.list(pasta.replace('/', '\\'))
                        .filter { it.fileName != "." && it.fileName != ".." }
                        .associate { info ->
                            info.fileName to Info(info.endOfFile, info.lastWriteTime.toEpochMillis())
                        }
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "tamanho e data indisponíveis em ${server.displayHost}/$share: $e")
                null
            }
        }

    /**
     * Copia para [destino] os arquivos pequenos da pasta cujo nome passa em
     * [quais] — as legendas ao lado do vídeo. Como na listagem, quem toca é o
     * VLC; este cliente só traz o que ele não enxerga. Falhando, devolve vazio.
     */
    suspend fun baixar(
        server: SmbServer,
        senha: String?,
        share: String,
        pasta: String,
        destino: File,
        teto: Long,
        quais: (String) -> Boolean,
    ): List<File> = runInterruptible(Dispatchers.IO) {
        try {
            noDisco(server, senha, share) { disco ->
                val dir = pasta.replace('/', '\\')
                disco.list(dir)
                    .filter { quais(it.fileName) && it.endOfFile <= teto }
                    .map { info ->
                        val caminho = if (dir.isEmpty()) info.fileName else "$dir\\${info.fileName}"
                        disco.openFile(
                            caminho, setOf(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN, null,
                        ).use { arquivo ->
                            File(destino, info.fileName).also { saida ->
                                arquivo.inputStream.use { entrada -> saida.outputStream().use { entrada.copyTo(it) } }
                            }
                        }
                    }
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "legendas ao lado indisponíveis em ${server.displayHost}/$share: $e")
            emptyList()
        }
    }

    /** Abre o compartilhamento com a conta certa e entrega o disco a [trabalho]. */
    private fun <T> noDisco(server: SmbServer, senha: String?, share: String, trabalho: (DiskShare) -> T): T {
        // Usuário em branco é convidado para o servidor, marcado ou não.
        if (!server.isGuest && server.username.isNotBlank()) {
            // O mesmo grupo de trabalho que o VLC recebe — ver SmbBrowser.credenciais.
            val conta = AuthenticationContext(server.username, (senha ?: "").toCharArray(), "WORKGROUP")
            return abrir(cliente, server, share, conta, trabalho)
        }
        // Há Samba que só aceita "guest" e há o que só aceita anônimo.
        return runCatching { abrir(cliente, server, share, AuthenticationContext.guest(), trabalho) }
            .recoverCatching { abrir(clienteSemSmb3, server, share, AuthenticationContext.guest(), trabalho) }
            .recoverCatching { abrir(clienteSemSmb3, server, share, AuthenticationContext.anonymous(), trabalho) }
            .getOrThrow()
    }

    private fun <T> abrir(
        cliente: SMBClient,
        server: SmbServer,
        share: String,
        conta: AuthenticationContext,
        trabalho: (DiskShare) -> T,
    ): T =
        cliente.connect(server.host, server.port).use { conexao: Connection ->
            conexao.authenticate(conta).use { sessao: Session ->
                (sessao.connectShare(share) as DiskShare).use(trabalho)
            }
        }
}
