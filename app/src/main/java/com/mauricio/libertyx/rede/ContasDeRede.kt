package com.mauricio.libertyx.rede

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.Prefs
import com.mauricio.libertyx.core.Textos
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Uma conta de servidor que fala HTTP: WebDAV (o Nextcloud entre eles),
 * Jellyfin ou Emby.
 *
 * O SMB continua com o próprio cadastro ([com.mauricio.libertyx.smb.SmbServer]):
 * ele tem porta, convidado e procura na rede, que nada disto usa. Aqui o
 * endereço é sempre uma URL completa.
 */
data class ContaDeRede(
    val id: String = UUID.randomUUID().toString(),
    val tipo: Tipo,
    val nome: String,
    /** Endereço já com `http(s)://`. */
    val endereco: String,
    val usuario: String = "",
    /** Jellyfin/Emby: o id do usuário no servidor, que vem do login. */
    val idNoServidor: String? = null,
) {
    enum class Tipo(val nome: String) {
        WEBDAV("WebDAV"), JELLYFIN("Jellyfin"), EMBY("Emby");

        /**
         * Jellyfin e Emby são o mesmo servidor por dentro — o Jellyfin nasceu
         * de uma cópia do Emby — e falam a mesma API.
         */
        val ehServidorDeMidia: Boolean get() = this != WEBDAV
    }

    /** Sem o esquema: é o que importa para reconhecer o servidor na lista. */
    val enderecoCurto: String
        get() = endereco.removePrefix("https://").removePrefix("http://").trim('/')
}

class ContasDeRede private constructor(context: Context) {

    private val prefs = Prefs.get(context)
    private val cofre: SharedPreferences = abrirCofre(context)

    private val _contas = mutableListOf<ContaDeRede>()
    val contas: List<ContaDeRede> get() = _contas.toList()

    init {
        runCatching {
            val raiz = JSONArray(prefs.getString(KEY, "[]"))
            for (i in 0 until raiz.length()) {
                val o = raiz.getJSONObject(i)
                _contas += ContaDeRede(
                    id = o.getString("id"),
                    tipo = ContaDeRede.Tipo.valueOf(o.getString("tipo")),
                    nome = o.getString("nome"),
                    endereco = o.getString("endereco"),
                    usuario = o.optString("usuario"),
                    idNoServidor = o.optString("idNoServidor").ifEmpty { null },
                )
            }
        }
    }

    fun byId(id: String): ContaDeRede? = _contas.firstOrNull { it.id == id }

    /**
     * @param segredo a senha (WebDAV) ou o token de acesso (Jellyfin/Emby);
     * `null` mantém o que já estava guardado.
     */
    fun salvar(conta: ContaDeRede, segredo: String?) {
        val i = _contas.indexOfFirst { it.id == conta.id }
        if (i >= 0) _contas[i] = conta else _contas += conta
        if (!segredo.isNullOrEmpty()) cofre.edit().putString(conta.id, segredo).apply()
        persistir()
    }

    fun remover(conta: ContaDeRede) {
        _contas.removeAll { it.id == conta.id }
        cofre.edit().remove(conta.id).apply()
        persistir()
    }

    fun segredo(conta: ContaDeRede): String? = cofre.getString(conta.id, null)

    private fun persistir() {
        val raiz = JSONArray()
        for (c in _contas) {
            raiz.put(JSONObject().apply {
                put("id", c.id); put("tipo", c.tipo.name); put("nome", c.nome)
                put("endereco", c.endereco); put("usuario", c.usuario)
                put("idNoServidor", c.idNoServidor ?: "")
            })
        }
        prefs.edit().putString(KEY, raiz.toString()).apply()
    }

    /** O mesmo cofre cifrado das senhas de SMB, num arquivo à parte. */
    private fun abrirCofre(context: Context): SharedPreferences = runCatching {
        val chave = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "libertyx.secrets.contas",
            chave,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.applicationContext.getSharedPreferences("libertyx.secrets.contas.plain", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY = "libertyx.contasDeRede"

        @Volatile private var instance: ContasDeRede? = null

        fun get(context: Context): ContasDeRede =
            instance ?: synchronized(this) {
                instance ?: ContasDeRede(context.applicationContext).also { instance = it }
            }

        /**
         * O endereço que o player abre. Igual ao [uriDeReproducao], menos no
         * Jellyfin e no Emby: o canal de TV ao vivo já aberto (ver
         * [Conversoes]) e a qualidade convertida pelo servidor (ver [Qualidade]).
         */
        fun uriParaTocar(context: Context, origem: MediaOrigin): Uri? {
            if (origem !is MediaOrigin.Midia) return uriDeReproducao(context, origem)
            Conversoes.canal(origem.itemId)?.let { return it }
            val loja = get(context)
            val conta = loja.byId(origem.contaId) ?: return null
            val servidor = ServidorDeMidia(conta, loja.segredo(conta))
            val kbps = Qualidade.da(context, conta.id)
            if (kbps > 0) {
                val sessao = Conversoes.comecar(origem.itemId)
                return servidor.urlDaConversao(origem.itemId, kbps, sessao, Conversoes.audio(origem.itemId))
            }
            Conversoes.encerrar(origem.itemId)
            return servidor.urlDoVideo(origem.itemId)
        }

        /** O endereço que o VLC abre, para tocar ou para tirar a miniatura. */
        fun uriDeReproducao(context: Context, origem: MediaOrigin): Uri? {
            val loja = get(context)
            return when (origem) {
                is MediaOrigin.WebDav -> loja.byId(origem.contaId)
                    ?.let { WebDav(it, loja.segredo(it)).urlDeReproducao(origem.caminho) }
                is MediaOrigin.Midia -> loja.byId(origem.contaId)
                    ?.let { ServidorDeMidia(it, loja.segredo(it)).urlDoVideo(origem.itemId) }
                else -> null
            }
        }

        /**
         * Um cliente HTTP para o app inteiro.
         *
         * O `HttpURLConnection` do Android recusa métodos fora da lista do HTTP
         * comum, e listar pasta no WebDAV é `PROPFIND`.
         */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** Erro de servidor HTTP com uma frase que diga o que fazer. */
class ErroDeServidor(mensagem: String) : Exception(mensagem) {
    companion object {
        fun http(codigo: Int): ErroDeServidor = ErroDeServidor(
            when (codigo) {
                401, 403 -> Textos.get(R.string.erro_credenciais)
                404, 405 -> Textos.get(R.string.erro_nao_e_pasta)
                else -> Textos.get(R.string.erro_http, codigo)
            }
        )
    }
}
