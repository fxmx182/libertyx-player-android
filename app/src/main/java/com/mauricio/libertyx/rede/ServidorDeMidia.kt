package com.mauricio.libertyx.rede

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.Prefs
import com.mauricio.libertyx.core.Textos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Cliente de Jellyfin e Emby.
 *
 * O app lê só o catálogo pela API e toca o **arquivo original** pelo VLC. Num
 * servidor sem aceleração de vídeo isso muda tudo: o MKV com HEVC e áudio AC-3
 * sai do disco como está, sem o servidor converter nada, e a rolagem é a mesma
 * do arquivo local.
 */
class ServidorDeMidia(private val conta: ContaDeRede, private val token: String?) {

    data class Item(
        val id: String,
        val nome: String,
        val tipo: String,
        val ehPasta: Boolean,
        val tipoDeColecao: String?,
        /** Em segundos; o servidor conta em décimos de microssegundo. */
        val duracao: Double?,
        val ano: Int?,
        val numero: Int?,
        val criado: Long?,
        /** Episódio: a série de que ele faz parte — na busca, é o que o situa. */
        val serie: String? = null,
    ) {
        /**
         * Episódio com o número na frente, filme com o ano: é como se acha o
         * que se procura numa lista longa.
         */
        val titulo: String
            get() = when {
                tipo == "Episode" && numero != null -> "$numero. $nome"
                tipo == "Movie" && ano != null -> "$nome ($ano)"
                else -> nome
            }

        val ehVideo: Boolean get() = !ehPasta && tipo in setOf("Movie", "Episode", "Video", "MusicVideo")
    }

    /** Bibliotecas do usuário (Filmes, Séries, Vídeos caseiros…). */
    suspend fun bibliotecas(): List<Item> {
        val itens = lista(pedir("/Users/$usuarioId/Views"))
        // Música, livros e fotos não têm o que fazer num player de vídeo.
        val fora = setOf("music", "books", "photos", "playlists", "livetv", "musicvideos")
        return itens.filter { it.tipoDeColecao !in fora }
    }

    /**
     * O que há dentro de uma biblioteca, série ou temporada.
     *
     * Bibliotecas de filmes e de séries são guardadas em pastas, mas quem
     * navega espera ver os filmes e as séries direto, não a estrutura de disco
     * — então elas são lidas de forma recursiva, filtradas pelo tipo.
     */
    suspend fun itens(pai: Item): List<Item> {
        val consulta = mutableListOf(
            "ParentId" to pai.id,
            "Fields" to "DateCreated",
            "SortBy" to "ParentIndexNumber,IndexNumber,SortName",
        )
        when (pai.tipoDeColecao) {
            "movies" -> consulta += listOf("Recursive" to "true", "IncludeItemTypes" to "Movie")
            "tvshows" -> consulta += listOf("Recursive" to "true", "IncludeItemTypes" to "Series")
        }
        return lista(pedir("/Users/$usuarioId/Items", consulta))
    }

    /** Filmes e episódios cujo nome tem [termo], de todas as bibliotecas. */
    suspend fun buscar(termo: String): List<Item> = lista(
        pedir(
            "/Users/$usuarioId/Items",
            listOf(
                "searchTerm" to termo,
                "Recursive" to "true",
                "IncludeItemTypes" to "Movie,Episode,Video,MusicVideo",
                "Fields" to "DateCreated",
                "Limit" to "40",
            ),
        )
    ).filter { it.ehVideo }

    /** O arquivo original, sem conversão (`static=true`). */
    fun urlDoVideo(id: String): Uri? =
        url("/Videos/$id/stream", listOf("static" to "true", "MediaSourceId" to id) + chaveNaConsulta)

    /**
     * Imagem do item: `Thumb` e `Backdrop` são deitadas, como as capas do app;
     * `Primary` de filme é o cartaz em pé, e fica por último.
     */
    suspend fun imagem(id: String): Bitmap? = withContext(Dispatchers.IO) {
        for (tipo in listOf("Thumb", "Backdrop", "Primary")) {
            val url = url("/Items/$id/Images/$tipo", listOf("maxWidth" to "480", "quality" to "80") + chaveNaConsulta)
                ?: continue
            val imagem = runCatching {
                ContasDeRede.http.newCall(Request.Builder().url(url.toString()).build()).execute().use { r ->
                    if (r.code == 200) r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
                }
            }.getOrNull()
            if (imagem != null) return@withContext imagem
        }
        null
    }

    private val usuarioId: String get() = conta.idNoServidor.orEmpty()

    /** O Jellyfin novo lê `ApiKey`; o Emby, `api_key`. Os dois servem aos dois. */
    private val chaveNaConsulta: List<Pair<String, String>>
        get() = token?.let { listOf("ApiKey" to it, "api_key" to it) }.orEmpty()

    private val cabecalho: String
        get() = buildString {
            append("MediaBrowser Client=\"LibertyX Player\", Device=\"Android\", ")
            append("DeviceId=\"$idDoAparelho\", Version=\"1.0\"")
            token?.let { append(", Token=\"$it\"") }
        }

    private fun url(caminho: String, consulta: List<Pair<String, String>> = emptyList()): Uri? {
        val base = conta.endereco.trimEnd('/')
        val uri = Uri.parse(base + caminho)
        if (uri.scheme == null) return null
        return uri.buildUpon().apply { consulta.forEach { (k, v) -> appendQueryParameter(k, v) } }.build()
    }

    private suspend fun pedir(
        caminho: String,
        consulta: List<Pair<String, String>> = emptyList(),
        corpo: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = url(caminho, consulta) ?: throw ErroDeServidor(Textos.get(R.string.erro_endereco_invalido))
        val pedido = Request.Builder().url(url.toString())
            // O Jellyfin lê `Authorization`; o Emby, `X-Emby-Authorization`.
            .header("Authorization", cabecalho)
            .header("X-Emby-Authorization", cabecalho)
            .header("Accept", "application/json")
            .apply { corpo?.let { post(it.toString().toRequestBody("application/json".toMediaType())) } }
            .build()
        ContasDeRede.http.newCall(pedido).execute().use { resposta ->
            val naoEh = ErroDeServidor(Textos.get(R.string.erro_nao_e_servidor, conta.tipo.nome))
            if (resposta.code == 404) throw naoEh
            if (!resposta.isSuccessful) throw ErroDeServidor.http(resposta.code)
            runCatching { JSONObject(resposta.body?.string().orEmpty()) }.getOrElse { throw naoEh }
        }
    }

    private fun lista(json: JSONObject): List<Item> {
        val itens = json.optJSONArray("Items") ?: return emptyList()
        return (0 until itens.length()).map { i ->
            val o = itens.getJSONObject(i)
            Item(
                id = o.getString("Id"),
                nome = o.optString("Name"),
                tipo = o.optString("Type"),
                ehPasta = o.optBoolean("IsFolder"),
                tipoDeColecao = o.optString("CollectionType").ifEmpty { null },
                duracao = o.optLong("RunTimeTicks", 0).takeIf { it > 0 }?.let { it / 10_000_000.0 },
                ano = o.optInt("ProductionYear", 0).takeIf { it > 0 },
                numero = if (o.has("IndexNumber")) o.optInt("IndexNumber") else null,
                criado = o.optString("DateCreated").takeIf { it.length >= 19 }
                    ?.let { runCatching { formatoDeData().parse(it.take(19))?.time }.getOrNull() },
                serie = o.optString("SeriesName").ifEmpty { null },
            )
        }
    }

    companion object {
        /** O servidor lista os aparelhos conectados por este id. */
        private val idDoAparelho: String by lazy {
            val prefs = Prefs.get(Textos.app)
            prefs.getString("libertyx.idDoAparelho", null)
                ?: UUID.randomUUID().toString().also { prefs.edit().putString("libertyx.idDoAparelho", it).apply() }
        }

        /** Sete casas de fração e fuso no fim: só os segundos interessam para ordenar. */
        private fun formatoDeData() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        /**
         * Entra com usuário e senha e devolve o id do usuário e o token.
         *
         * A senha não fica guardada: o token basta para tudo e pode ser
         * revogado no painel do servidor sem trocar a senha.
         */
        suspend fun entrar(conta: ContaDeRede, senha: String): Pair<String, String> {
            val resposta = ServidorDeMidia(conta, null).pedir(
                "/Users/AuthenticateByName",
                corpo = JSONObject().put("Username", conta.usuario).put("Pw", senha),
            )
            val usuario = resposta.optJSONObject("User")?.optString("Id").orEmpty()
            val token = resposta.optString("AccessToken")
            if (usuario.isEmpty() || token.isEmpty()) {
                throw ErroDeServidor(Textos.get(R.string.erro_nao_e_servidor, conta.tipo.nome))
            }
            return usuario to token
        }
    }
}
