package com.mauricio.libertyx.rede

import android.net.Uri
import android.util.Base64
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.Textos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Cliente WebDAV — o suficiente para navegar por pastas e entregar ao VLC o
 * endereço de cada vídeo.
 *
 * É por aqui que entra o Nextcloud: os arquivos dele ficam em
 * `/remote.php/dav/files/<usuário>/`. Quem toca é o VLC, por HTTP comum, com
 * busca por faixa de bytes; listar pasta é a única coisa que exige o
 * vocabulário do WebDAV (`PROPFIND`).
 */
class WebDav(private val conta: ContaDeRede, private val senha: String?) {

    data class Entrada(
        val nome: String,
        /** Caminho no servidor, já decodificado e começando por `/`. */
        val caminho: String,
        val ehPasta: Boolean,
        val tamanho: Long?,
        val modificado: Long?,
    )

    /** Pasta de partida: o caminho que veio no endereço da conta. */
    val raiz: String get() = Uri.parse(conta.endereco).path.orEmpty().ifEmpty { "/" }

    suspend fun listar(caminho: String): List<Entrada> = withContext(Dispatchers.IO) {
        val url = url(caminho, pasta = true) ?: throw ErroDeServidor(Textos.get(R.string.erro_endereco_invalido))
        val corpo = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
            <d:resourcetype/><d:getcontentlength/><d:getlastmodified/>
            </d:prop></d:propfind>
        """.trimIndent()
        val pedido = Request.Builder().url(url.toString())
            .method("PROPFIND", corpo.toRequestBody())
            // Só o nível de baixo: `infinity` faria o servidor descer a árvore
            // inteira, e muitos recusam.
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .apply { autorizacao?.let { header("Authorization", it) } }
            .build()

        ContasDeRede.http.newCall(pedido).execute().use { resposta ->
            // 207 é a resposta própria do WebDAV. Um 200 aqui costuma ser uma
            // página de login, não uma listagem.
            when (resposta.code) {
                207 -> Unit
                200 -> throw ErroDeServidor(Textos.get(R.string.erro_nao_e_webdav))
                else -> throw ErroDeServidor.http(resposta.code)
            }
            val pedida = normalizar(caminho)
            ler(resposta.body?.string().orEmpty()).mapNotNull { bruta ->
                val c = caminhoDoHref(bruta.href)
                // A própria pasta vem na resposta, junto com o que tem dentro.
                if (normalizar(c) == pedida) return@mapNotNull null
                val nome = c.trimEnd('/').substringAfterLast('/')
                // Ocultos do sistema (`.DS_Store`, `.nomedia`) não interessam.
                if (nome.isEmpty() || nome.startsWith(".")) return@mapNotNull null
                Entrada(nome, c, bruta.ehPasta, bruta.tamanho, bruta.modificado)
            }
        }
    }

    /**
     * Endereço de reprodução, com usuário e senha embutidos.
     *
     * No HTTP o VLC lê as credenciais da própria URL (só avisa no log que o
     * jeito é antigo) — diferente do SMB, onde elas precisam ir por opção. A
     * URL só existe em memória, na hora de tocar.
     */
    fun urlDeReproducao(caminho: String): Uri? {
        val base = url(caminho, pasta = false) ?: return null
        if (conta.usuario.isEmpty()) return base
        val acesso = escapar(conta.usuario) + (senha?.takeIf { it.isNotEmpty() }?.let { ":" + escapar(it) } ?: "")
        return base.buildUpon().encodedAuthority(acesso + "@" + base.encodedAuthority).build()
    }

    private val autorizacao: String?
        get() {
            if (conta.usuario.isEmpty()) return null
            val par = "${conta.usuario}:${senha.orEmpty()}"
            return "Basic " + Base64.encodeToString(par.toByteArray(), Base64.NO_WRAP)
        }

    /**
     * Esquema, servidor e porta da conta, com o caminho codificado pedaço a
     * pedaço — nome de arquivo com espaço, acento ou `#` é o normal.
     */
    private fun url(caminho: String, pasta: Boolean): Uri? {
        val base = Uri.parse(conta.endereco)
        if (base.scheme == null || base.encodedAuthority == null) return null
        var codificado = "/" + caminho.split('/').filter { it.isNotEmpty() }.joinToString("/") { escapar(it) }
        if (pasta && !codificado.endsWith("/")) codificado += "/"
        return Uri.Builder().scheme(base.scheme).encodedAuthority(base.encodedAuthority)
            .encodedPath(codificado).build()
    }

    private class Bruta(
        var href: String = "",
        var ehPasta: Boolean = false,
        var tamanho: Long? = null,
        var modificado: Long? = null,
    )

    /**
     * Lê a resposta `multistatus`. O prefixo do espaço de nomes muda de
     * servidor para servidor (`d:`, `D:`, nenhum), então só o nome local de
     * cada elemento conta.
     */
    private fun ler(xml: String): List<Bruta> {
        val lidas = mutableListOf<Bruta>()
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(xml.reader())
        var atual: Bruta? = null
        var texto = StringBuilder()
        runCatching {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        texto = StringBuilder()
                        when (parser.name) {
                            "response" -> atual = Bruta()
                            "collection" -> atual?.ehPasta = true
                        }
                    }
                    XmlPullParser.TEXT -> texto.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        val valor = texto.toString().trim()
                        when (parser.name) {
                            "href" -> atual?.let { if (it.href.isEmpty()) it.href = valor }
                            "getcontentlength" -> atual?.tamanho = valor.toLongOrNull()
                            "getlastmodified" -> atual?.modificado =
                                runCatching { formatoDeData().parse(valor)?.time }.getOrNull()
                            "response" -> {
                                atual?.takeIf { it.href.isNotEmpty() }?.let(lidas::add)
                                atual = null
                            }
                        }
                        texto = StringBuilder()
                    }
                }
            }
        }
        return lidas
    }

    companion object {
        fun escapar(texto: String): String = Uri.encode(texto)

        /** O `href` pode vir como caminho (`/dav/a%20b/`) ou como URL completa. */
        fun caminhoDoHref(href: String): String {
            val limpo = href.trim()
            return if (limpo.startsWith("http")) Uri.parse(limpo).path.orEmpty() else Uri.decode(limpo)
        }

        fun normalizar(caminho: String): String = caminho.trimEnd('/').ifEmpty { "/" }

        private fun formatoDeData() = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
    }
}
