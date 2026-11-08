package com.mauricio.libertyx.player

import android.content.Context
import org.videolan.libvlc.LibVLC

/**
 * A instância única do motor.
 *
 * O `LibVLC` carrega uma biblioteca nativa de dezenas de megabytes e monta o
 * catálogo de módulos; criar um por tela custaria isso a cada abertura. Uma só,
 * viva enquanto o processo viver — é assim que o próprio VLC do Android faz.
 */
object Vlc {

    /**
     * Uma por tamanho de legenda. O VLC 3 só lê o tamanho do texto da
     * legenda (`--sub-text-scale`) quando o motor nasce — nem por vídeo,
     * nem com o vídeo tocando. Mudar o tamanho é usar outro motor; os
     * anteriores ficam, porque miniaturas em andamento ainda os usam, e a
     * pessoa costuma ir e voltar entre dois ou três tamanhos.
     */
    private val instancias = mutableMapOf<Int, LibVLC>()

    fun get(context: Context): LibVLC {
        val escala = TamanhoDaLegenda.atual(context)
        return synchronized(this) {
            instancias.getOrPut(escala) { LibVLC(context.applicationContext, options(escala)) }
        }
    }

    private fun options(escala: Int) = arrayListOf(
        // Acelerar sem deixar a voz fina. É o gesto de "segurar para 2×", que
        // não serviria para nada se a fala virasse guincho.
        "--audio-time-stretch",
        // O VLC do Android liga isto sozinho e vale a pena: em imagem parada
        // pelo scrub, sem ele o quadro sai borrado.
        "--avcodec-skiploopfilter", "0",
        // Reconectar sozinho: o filme não pode acabar porque o Wi-Fi piscou.
        "--http-reconnect",
        // Legenda ao lado do arquivo entra sozinha, como no MX Player.
        "--sub-autodetect-file",
        // O tamanho da legenda escolhido no player (ver TamanhoDaLegenda).
        "--sub-text-scale=$escala",
        // Log só o essencial: em depuração o VLC é escandalosamente falante,
        // e o logcat cheio esconde justamente a linha que interessa.
        "-v",
    )
}

/** O tamanho do texto da legenda, em porcentagem do normal. Vale para todos os vídeos. */
object TamanhoDaLegenda {
    val opcoes = listOf(50, 60, 70, 80, 90, 100, 115, 130, 150, 175, 200, 250, 300)
    private const val CHAVE = "libertyx.legenda.tamanho"

    fun atual(context: Context): Int =
        com.mauricio.libertyx.core.Prefs.get(context).getInt(CHAVE, 100)

    fun definir(context: Context, escala: Int) {
        com.mauricio.libertyx.core.Prefs.get(context).edit().putInt(CHAVE, escala).apply()
    }
}
