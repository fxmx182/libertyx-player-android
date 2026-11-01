package com.mauricio.libertyx.core

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Os vídeos abertos por último, de qualquer origem.
 *
 * A faixa "Continuar assistindo" vinha das marcas de retomada casadas com a
 * biblioteca do aparelho — e vídeo de servidor nunca está nela, então sumia
 * dali. Aqui fica o próprio item, com a origem inteira: tocar de novo pelo
 * histórico funciona igual para o aparelho, SMB, WebDAV e Jellyfin. A senha
 * não vem junto: a origem só aponta para a conta salva.
 */
class Historico private constructor(context: Context) {

    private val prefs = Prefs.get(context)
    private val _itens = mutableListOf<MediaItem>()
    val itens: List<MediaItem> get() = _itens.toList()

    init {
        runCatching {
            val raiz = JSONArray(prefs.getString(KEY, "[]"))
            for (i in 0 until raiz.length()) ler(raiz.getJSONObject(i))?.let(_itens::add)
        }
    }

    /** Vai para o começo; o mesmo vídeo não aparece duas vezes. */
    fun registrar(item: MediaItem) {
        val chave = item.origin.resumeKey
        _itens.removeAll { it.origin.resumeKey == chave }
        _itens.add(0, item)
        while (_itens.size > LIMITE) _itens.removeAt(_itens.lastIndex)
        persistir()
    }

    fun remover(item: MediaItem) {
        val chave = item.origin.resumeKey
        _itens.removeAll { it.origin.resumeKey == chave }
        persistir()
    }

    private fun persistir() {
        val raiz = JSONArray()
        _itens.forEach { raiz.put(escrever(it)) }
        prefs.edit().putString(KEY, raiz.toString()).apply()
        versao.intValue++
    }

    private fun escrever(item: MediaItem) = JSONObject().apply {
        put("id", item.id); put("titulo", item.title)
        item.fileSize?.let { put("tamanho", it) }
        item.modifiedAt?.let { put("modificado", it) }
        item.duration?.let { put("duracao", it) }
        when (val o = item.origin) {
            is MediaOrigin.Local -> { put("tipo", "local"); put("uri", o.uri.toString()); o.path?.let { put("caminho", it) } }
            is MediaOrigin.Smb -> {
                put("tipo", "smb"); put("servidor", o.serverId); put("host", o.host)
                put("share", o.share); put("caminho", o.path)
            }
            is MediaOrigin.Remote -> { put("tipo", "remoto"); put("uri", o.uri.toString()) }
            is MediaOrigin.WebDav -> { put("tipo", "webdav"); put("conta", o.contaId); put("caminho", o.caminho) }
            is MediaOrigin.Midia -> { put("tipo", "midia"); put("conta", o.contaId); put("item", o.itemId) }
        }
    }

    private fun ler(o: JSONObject): MediaItem? {
        val origem = when (o.optString("tipo")) {
            "local" -> MediaOrigin.Local(Uri.parse(o.getString("uri")), o.optString("caminho").ifEmpty { null })
            "smb" -> MediaOrigin.Smb(o.getString("servidor"), o.getString("host"), o.getString("share"), o.getString("caminho"))
            "remoto" -> MediaOrigin.Remote(Uri.parse(o.getString("uri")))
            "webdav" -> MediaOrigin.WebDav(o.getString("conta"), o.getString("caminho"))
            "midia" -> MediaOrigin.Midia(o.getString("conta"), o.getString("item"))
            else -> return null
        }
        return MediaItem(
            id = o.getString("id"),
            title = o.getString("titulo"),
            origin = origem,
            fileSize = if (o.has("tamanho")) o.getLong("tamanho") else null,
            modifiedAt = if (o.has("modificado")) o.getLong("modificado") else null,
            duration = if (o.has("duracao")) o.getDouble("duracao") else null,
        )
    }

    companion object {
        private const val KEY = "libertyx.historico"
        private const val LIMITE = 40

        /** Muda a cada alteração: a tela que lê o histórico se refaz sozinha. */
        val versao = mutableIntStateOf(0)

        @Volatile private var instance: Historico? = null

        fun get(context: Context): Historico =
            instance ?: synchronized(this) {
                instance ?: Historico(context.applicationContext).also { instance = it }
            }
    }
}
