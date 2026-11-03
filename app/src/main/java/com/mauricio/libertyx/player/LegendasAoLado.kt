package com.mauricio.libertyx.player

import android.content.Context
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.rede.ContasDeRede
import com.mauricio.libertyx.rede.ServidorDeMidia
import com.mauricio.libertyx.rede.WebDav
import com.mauricio.libertyx.smb.SmbMetadados
import com.mauricio.libertyx.smb.SmbServerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * As legendas que moram ao lado do vídeo, em arquivo próprio (`.srt`, `.ass`).
 *
 * O VLC acha sozinho as do aparelho (`--sub-autodetect-file`), mas só em
 * pasta local: no SMB, no WebDAV e no Jellyfin ele não enxerga a pasta. Aqui
 * o app lista a pasta do vídeo, ou pede ao Jellyfin as legendas "externas"
 * dele, copia as que combinam para o cache — são poucos KB — e o player as
 * entrega ao VLC como arquivo local.
 *
 * Combina o que começa com o nome do vídeo, como no Jellyfin e no Plex:
 * `Filme.srt`, `Filme.pt.srt`, `Filme.pt.forced.srt`. O nome do arquivo segue
 * junto, e é por ele que a regra da legenda forçada a reconhece.
 */
object LegendasAoLado {

    private val EXTENSOES = setOf("srt", "ass", "ssa", "vtt")

    /** Legenda bem maior que isto não é legenda: não vale baixar. */
    private const val TETO = 2L * 1024 * 1024

    fun combina(video: String, arquivo: String): Boolean {
        if (arquivo.substringAfterLast('.', "").lowercase() !in EXTENSOES) return false
        val base = video.substringBeforeLast('.').lowercase()
        return base.isNotEmpty() && arquivo.lowercase().startsWith("$base.")
    }

    /**
     * Os arquivos já no aparelho, prontos para o VLC. Nunca lança: sem rede
     * ou sem legenda, o vídeo toca igual, só sem elas.
     */
    suspend fun buscar(context: Context, origem: MediaOrigin): List<File> = withContext(Dispatchers.IO) {
        val destino = File(context.cacheDir, "legendas/" + origem.resumeKey.hashCode().toUInt().toString(16))
        // Só as do vídeo da vez: as dos anteriores não servem mais.
        destino.parentFile?.deleteRecursively()
        destino.mkdirs()
        runCatching {
            when (origem) {
                is MediaOrigin.Smb -> doSmb(context, origem, destino)
                is MediaOrigin.WebDav -> doWebDav(context, origem, destino)
                is MediaOrigin.Midia -> doServidor(context, origem, destino)
                // No aparelho o próprio VLC acha; por endereço solto não há pasta.
                is MediaOrigin.Local, is MediaOrigin.Remote -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun doSmb(context: Context, origem: MediaOrigin.Smb, destino: File): List<File> {
        val loja = SmbServerStore.get(context)
        val servidor = loja.byId(origem.serverId) ?: return emptyList()
        val video = origem.path.substringAfterLast('/')
        val pasta = origem.path.substringBeforeLast('/', "")
        return SmbMetadados.baixar(servidor, loja.password(servidor), origem.share, pasta, destino, TETO) {
            combina(video, it)
        }
    }

    private suspend fun doWebDav(context: Context, origem: MediaOrigin.WebDav, destino: File): List<File> {
        val loja = ContasDeRede.get(context)
        val conta = loja.byId(origem.contaId) ?: return emptyList()
        val webDav = WebDav(conta, loja.segredo(conta))
        val video = origem.caminho.trimEnd('/').substringAfterLast('/')
        val pasta = origem.caminho.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        return webDav.listar(pasta)
            .filter { !it.ehPasta && combina(video, it.nome) && (it.tamanho ?: 0) <= TETO }
            .mapNotNull { entrada ->
                runCatching { File(destino, entrada.nome).also { webDav.baixar(entrada.caminho, it) } }.getOrNull()
            }
    }

    private suspend fun doServidor(context: Context, origem: MediaOrigin.Midia, destino: File): List<File> {
        val loja = ContasDeRede.get(context)
        val conta = loja.byId(origem.contaId) ?: return emptyList()
        val servidor = ServidorDeMidia(conta, loja.segredo(conta))
        return servidor.legendasExternas(origem.itemId).mapNotNull { (nome, texto) ->
            runCatching { File(destino, nome).also { it.writeText(texto) } }.getOrNull()
        }
    }
}
