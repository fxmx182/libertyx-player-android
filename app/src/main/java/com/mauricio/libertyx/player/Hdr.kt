package com.mauricio.libertyx.player

import android.app.Activity
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.resumeKey

/**
 * Quando o vídeo vai direto para a tela, com o HDR junto.
 *
 * A TextureView passa pela composição da janela, que é SDR: filme HDR sai
 * lavado. Na TV o app usa sempre a SurfaceView. No celular e no tablet ela
 * custaria o zoom de pinça e a captura de quadro direta (ver
 * `VlcEngine.attach`), então só vale quando os dois lados são HDR: a tela do
 * aparelho mostra HDR e o vídeo é HDR. Nos outros casos, nada muda.
 */
object Hdr {

    /** Vídeos do servidor que a página dele disse serem HDR (VideoRange do Jellyfin). */
    private val marcados = mutableSetOf<String>()

    @Synchronized
    fun marcar(chave: String) { marcados += chave }

    /** A tela deste aparelho mostra HDR. */
    fun telaMostra(activity: Activity): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val tela = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
        if (Build.VERSION.SDK_INT >= 26) tela?.isHdr == true
        else tela?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    }.getOrDefault(false)

    /**
     * O vídeo é HDR. Do servidor, pelo que a página dele disse; do aparelho,
     * pelo que o extrator do Android lê do arquivo (a curva PQ do HDR10 ou a
     * HLG). Arquivo de rede sem a página do servidor (SMB, WebDAV) fica como
     * SDR: abrir o arquivo duas vezes só para isso atrasaria todo vídeo.
     */
    fun ehHdr(context: Context, item: MediaItem): Boolean = when (val origem = item.origin) {
        is MediaOrigin.Midia -> synchronized(this) { item.origin.resumeKey in marcados }
        is MediaOrigin.Local -> runCatching {
            val extrator = MediaExtractor()
            try {
                extrator.setDataSource(context, origem.uri, null)
                (0 until extrator.trackCount).any { i ->
                    val formato = extrator.getTrackFormat(i)
                    val mime = formato.getString(MediaFormat.KEY_MIME).orEmpty()
                    mime == "video/dolby-vision" || (mime.startsWith("video/") &&
                        formato.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
                        formato.getInteger(MediaFormat.KEY_COLOR_TRANSFER).let {
                            it == MediaFormat.COLOR_TRANSFER_ST2084 || it == MediaFormat.COLOR_TRANSFER_HLG
                        })
                }
            } finally {
                extrator.release()
            }
        }.getOrDefault(false)
        else -> false
    }
}
