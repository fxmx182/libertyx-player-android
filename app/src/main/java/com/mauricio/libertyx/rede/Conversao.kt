package com.mauricio.libertyx.rede

import android.content.Context
import android.net.Uri
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.Prefs
import com.mauricio.libertyx.core.Textos

/**
 * A qualidade de um Jellyfin ou Emby: o arquivo original, como sempre, ou uma
 * versão mais leve que o próprio servidor converte na hora — para ver fora de
 * casa com a internet fraca.
 *
 * É escolha de cada conta: em casa, pela rede local, o original é melhor em
 * tudo. A conversão custa processador ao servidor, então só acontece quando
 * alguém pede.
 */
object Qualidade {

    /** Em kbit/s; zero é o original. */
    val opcoes = listOf(0, 20_000, 10_000, 6_000, 4_000, 2_000, 1_000)

    fun nome(kbps: Int): String = if (kbps == 0) Textos.get(R.string.qualidade_original) else "${kbps / 1000} Mbps"

    fun da(context: Context, contaId: String): Int = Prefs.get(context).getInt(chave(contaId), 0)

    fun definir(context: Context, contaId: String, kbps: Int) {
        Prefs.get(context).edit().putInt(chave(contaId), kbps).apply()
    }

    private fun chave(contaId: String) = "libertyx.qualidade.$contaId"
}

/**
 * As conversões e os canais de TV ao vivo em andamento. O servidor os
 * reconhece pelo número da sessão, que tem de ser o mesmo no endereço do
 * vídeo e nos avisos de reprodução — é o "parou" com esse número que manda
 * ele desligar a conversão.
 */
object Conversoes {

    private val sessoes = mutableMapOf<String, String>()
    private val audios = mutableMapOf<String, Int?>()
    private val canais = mutableMapOf<String, Uri>()
    private val transmissoes = mutableMapOf<String, String?>()

    @Synchronized
    fun comecar(item: String): String = java.util.UUID.randomUUID().toString().also { sessoes[item] = it }

    @Synchronized
    fun sessao(item: String): String? = sessoes[item]

    @Synchronized
    fun encerrar(item: String) {
        sessoes.remove(item)
        canais.remove(item)
        transmissoes.remove(item)
    }

    /** Na conversão vai uma faixa de áudio só: a escolhida na página, pelo número do servidor. */
    @Synchronized
    fun pedirAudio(item: String, indice: Int?) { audios[item] = indice }

    @Synchronized
    fun audio(item: String): Int? = audios[item]

    @Synchronized
    fun registrarCanal(item: String, uri: Uri, sessao: String, transmissao: String?) {
        canais[item] = uri
        sessoes[item] = sessao
        transmissoes[item] = transmissao
    }

    @Synchronized
    fun canal(item: String): Uri? = canais[item]

    @Synchronized
    fun transmissao(item: String): String? = transmissoes[item]
}
