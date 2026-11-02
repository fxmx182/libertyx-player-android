package com.mauricio.libertyx.rede

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.Prefs
import com.mauricio.libertyx.core.ResumeStore
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.core.Textos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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
        val serieId: String? = null,
        val temporadaId: String? = null,
        /** O número da temporada do episódio. */
        val temporada: Int? = null,
        /** Onde a pessoa parou, em segundos, em qualquer aparelho. */
        val posicao: Double? = null,
        val visto: Boolean = false,
        /** Série ou temporada: quantos episódios faltam ver. */
        val naoVistos: Int? = null,
        /** Quando foi tocado pela última vez, em qualquer aparelho. */
        val ultimaVez: Long? = null,
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

        /** De 0 a 1, só quando há o que retomar. */
        val progresso: Float?
            get() {
                val p = posicao ?: return null
                val d = duracao ?: return null
                return if (p > 0 && d > 0) (p / d).toFloat().coerceIn(0f, 1f) else null
            }
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

    /** O que a pessoa começou e não terminou, do mais recente ao mais antigo. */
    suspend fun continuar(): List<Item> = lista(
        pedir(
            "/Users/$usuarioId/Items/Resume",
            listOf("Limit" to "24", "MediaTypes" to "Video", "Fields" to "DateCreated", "EnableUserData" to "true"),
        )
    ).filter { it.ehVideo }

    /** O próximo episódio de cada série que a pessoa está vendo. */
    suspend fun aSeguir(): List<Item> = lista(
        pedir(
            "/Shows/NextUp",
            listOf("UserId" to usuarioId, "Limit" to "24", "Fields" to "DateCreated", "EnableUserData" to "true"),
        )
    ).filter { it.ehVideo }

    /**
     * O que chegou por último numa biblioteca. Numa de séries o servidor
     * agrupa os episódios novos na série, como no app dele.
     */
    suspend fun recentes(biblioteca: Item): List<Item> = lista(
        pedirLista(
            "/Users/$usuarioId/Items/Latest",
            listOf("ParentId" to biblioteca.id, "Limit" to "16", "Fields" to "DateCreated", "EnableUserData" to "true"),
        )
    )

    /** Os episódios da temporada, na ordem: é a fila do "próximo" no player. */
    suspend fun temporada(episodio: Item): List<Item> {
        val serie = episodio.serieId ?: return listOf(episodio)
        val consulta = mutableListOf("UserId" to usuarioId, "Fields" to "DateCreated")
        episodio.temporadaId?.let { consulta += "SeasonId" to it }
        return lista(pedir("/Shows/$serie/Episodes", consulta)).filter { it.ehVideo }.ifEmpty { listOf(episodio) }
    }

    /**
     * Conta ao servidor o que está tocando, para o "Continuar assistindo" e o
     * "visto" valerem também no app dele, na TV e no navegador.
     *
     * [evento] é `null` no começo, `Progress` durante e `Stopped` no fim — e
     * é no `Stopped` perto do fim que o servidor marca como visto.
     */
    suspend fun informar(evento: String?, itemId: String, segundos: Double, pausado: Boolean, sessao: String) {
        val corpo = JSONObject()
            .put("ItemId", itemId)
            .put("MediaSourceId", itemId)
            .put("PositionTicks", (segundos.coerceAtLeast(0.0) * TICKS).toLong())
            .put("IsPaused", pausado)
            .put("CanSeek", true)
            .put("PlayMethod", "DirectPlay")
            .put("PlaySessionId", sessao)
        enviar("/Sessions/Playing" + evento?.let { "/$it" }.orEmpty(), corpo)
    }

    /** O arquivo original, sem conversão (`static=true`). */
    fun urlDoVideo(id: String): Uri? =
        url("/Videos/$id/stream", listOf("static" to "true", "MediaSourceId" to id) + chaveNaConsulta)

    /**
     * Imagem do item: `Thumb` e `Backdrop` são deitadas, como as capas do app;
     * `Primary` de filme é o cartaz em pé, e fica por último.
     */
    suspend fun imagem(
        id: String,
        tipos: List<String> = listOf("Thumb", "Backdrop", "Primary"),
        largura: Int = 480,
    ): Bitmap? = withContext(Dispatchers.IO) {
        for (tipo in tipos) {
            val url = url("/Items/$id/Images/$tipo", listOf("maxWidth" to "$largura", "quality" to "80") + chaveNaConsulta)
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

    val contaId: String get() = conta.id

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
    ): JSONObject {
        val texto = pedirTexto(caminho, consulta, corpo)
        return runCatching { JSONObject(texto) }.getOrElse { throw naoEhServidor() }
    }

    /** O `Latest` responde com a lista solta, sem o `Items` em volta. */
    private suspend fun pedirLista(caminho: String, consulta: List<Pair<String, String>>): JSONArray {
        val texto = pedirTexto(caminho, consulta, null)
        return runCatching { JSONArray(texto) }.getOrElse { throw naoEhServidor() }
    }

    /** Um aviso: o servidor responde sem corpo (204), e não há o que ler. */
    private suspend fun enviar(caminho: String, corpo: JSONObject) {
        pedirTexto(caminho, emptyList(), corpo)
    }

    private fun naoEhServidor() = ErroDeServidor(Textos.get(R.string.erro_nao_e_servidor, conta.tipo.nome))

    private suspend fun pedirTexto(
        caminho: String,
        consulta: List<Pair<String, String>>,
        corpo: JSONObject?,
    ): String = withContext(Dispatchers.IO) {
        val url = url(caminho, consulta) ?: throw ErroDeServidor(Textos.get(R.string.erro_endereco_invalido))
        val pedido = Request.Builder().url(url.toString())
            // O Jellyfin lê `Authorization`; o Emby, `X-Emby-Authorization`.
            .header("Authorization", cabecalho)
            .header("X-Emby-Authorization", cabecalho)
            .header("Accept", "application/json")
            .apply { corpo?.let { post(it.toString().toRequestBody("application/json".toMediaType())) } }
            .build()
        ContasDeRede.http.newCall(pedido).execute().use { resposta ->
            if (resposta.code == 404) throw naoEhServidor()
            if (!resposta.isSuccessful) throw ErroDeServidor.http(resposta.code)
            resposta.body?.string().orEmpty()
        }
    }

    private fun lista(json: JSONObject): List<Item> = lista(json.optJSONArray("Items") ?: JSONArray())

    private fun lista(itens: JSONArray): List<Item> {
        val lidos = (0 until itens.length()).map { i ->
            val o = itens.getJSONObject(i)
            val usuario = o.optJSONObject("UserData")
            Item(
                id = o.getString("Id"),
                nome = o.optString("Name"),
                tipo = o.optString("Type"),
                ehPasta = o.optBoolean("IsFolder"),
                tipoDeColecao = o.optString("CollectionType").ifEmpty { null },
                duracao = o.optLong("RunTimeTicks", 0).takeIf { it > 0 }?.let { it / TICKS },
                ano = o.optInt("ProductionYear", 0).takeIf { it > 0 },
                numero = if (o.has("IndexNumber")) o.optInt("IndexNumber") else null,
                criado = data(o.optString("DateCreated")),
                serie = o.optString("SeriesName").ifEmpty { null },
                serieId = o.optString("SeriesId").ifEmpty { null },
                temporadaId = o.optString("SeasonId").ifEmpty { null },
                temporada = if (o.has("ParentIndexNumber")) o.optInt("ParentIndexNumber") else null,
                posicao = usuario?.optLong("PlaybackPositionTicks", 0)?.takeIf { it > 0 }?.let { it / TICKS },
                visto = usuario?.optBoolean("Played") == true,
                naoVistos = usuario?.optInt("UnplayedItemCount", 0)?.takeIf { it > 0 },
                ultimaVez = usuario?.optString("LastPlayedDate")?.let(::data),
            )
        }
        lembrarOndeParou(lidos)
        return lidos
    }

    /**
     * Toda lista que chega do servidor diz onde a pessoa parou em cada vídeo.
     * Guardar isso junto das marcas do aparelho é o que faz o "Você parou
     * em…" do player valer para o que foi visto na TV ou no navegador.
     */
    private fun lembrarOndeParou(itens: List<Item>) {
        val marcas = ResumeStore.get(Textos.app)
        for (item in itens) {
            if (!item.ehVideo) continue
            val quando = item.ultimaVez ?: continue
            val chave = MediaOrigin.Midia(conta.id, item.id).resumeKey
            marcas.mesclar(chave, item.posicao, item.duracao ?: 0.0, quando, item.visto)
        }
    }

    private fun data(texto: String): Long? = texto.takeIf { it.length >= 19 }
        ?.let { runCatching { formatoDeData().parse(it.take(19))?.time }.getOrNull() }

    companion object {
        /** O servidor conta o tempo em décimos de microssegundo. */
        private const val TICKS = 10_000_000.0

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
