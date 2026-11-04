package com.mauricio.libertyx.player

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * O índice de fontes do VLC, montado antes do primeiro vídeo.
 *
 * Para desenhar legenda e texto na tela, o VLC do Android usa o fontconfig:
 * o Android não oferece ao código nativo um jeito de perguntar "que fonte tem
 * este caractere", então o fontconfig abre cada uma das ~200 fontes de
 * `/system/fonts` (CJK e emoji incluídas) e grava um índice em
 * `app_vlc/.cache/fontconfig`. Isso acontece na criação da saída de vídeo, e
 * o filme não começa enquanto não termina: na primeira reprodução depois de
 * instalar, eram 20 s no emulador e perto de um minuto em celular e TV. O
 * iOS não sofre disso — lá o VLC pergunta ao CoreText.
 *
 * Aqui o índice é montado logo na primeira abertura do app, por uma
 * reprodução muda e invisível de um vídeo de um segundo, enquanto a pessoa
 * ainda está no guia ou na biblioteca. Se ela tocar num vídeo antes do fim,
 * o VLC espera esta mesma montagem (ela é protegida por uma trava dentro
 * dele) em vez de começar outra. Com o índice pronto, nada disto roda: é
 * o único caso em que o motor sobe sem alguém ter tocado num vídeo.
 */
object IndiceDeFontes {

    private const val TAG = "LibertyX"

    /** Dá tempo à primeira tela de aparecer antes de carregar a biblioteca nativa. */
    private const val ESPERA_MS = 1_500L

    /** Num aparelho lento a montagem passa de um minuto; isto é só um teto. */
    private const val PRAZO_MS = 5 * 60_000L

    private val iniciado = AtomicBoolean(false)
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun preparar(context: Context) {
        val app = context.applicationContext
        if (pronto(app) || !iniciado.compareAndSet(false, true)) return
        escopo.launch {
            delay(ESPERA_MS)
            val inicio = System.currentTimeMillis()
            runCatching { montar(app) }
                .onFailure { Log.w(TAG, "índice de fontes: falhou ($it)") }
            Log.i(TAG, "índice de fontes: ${if (pronto(app)) "pronto" else "não ficou pronto"} em ${System.currentTimeMillis() - inicio} ms")
        }
    }

    /** O `HOME` que o libVLC dá ao fontconfig é o `app_vlc` do app. */
    private fun pronto(context: Context): Boolean =
        File(context.getDir("vlc", Context.MODE_PRIVATE), ".cache/fontconfig")
            .listFiles { f -> f.name.contains(".cache-") }
            ?.isNotEmpty() == true

    private suspend fun montar(context: Context) {
        // O VLC não lê dentro do APK: o vídeo vai para o cache antes.
        val video = File(context.cacheDir, "preparo.mp4")
        withContext(Dispatchers.IO) {
            if (!video.exists()) {
                context.assets.open("preparo.mp4").use { entrada ->
                    video.outputStream().use { entrada.copyTo(it) }
                }
            }
        }

        val libVlc = Vlc.get(context)
        val leitor = ImageReader.newInstance(64, 36, PixelFormat.RGBX_8888, 2)
        // Os quadros não interessam, mas têm de ser consumidos.
        leitor.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val tocador = withContext(Dispatchers.Main) { MediaPlayer(libVlc) }
        val fim = CompletableDeferred<Unit>()

        try {
            withContext(Dispatchers.Main) {
                tocador.setEventListener { evento ->
                    when (evento.type) {
                        MediaPlayer.Event.EndReached,
                        MediaPlayer.Event.EncounteredError,
                        MediaPlayer.Event.Stopped -> fim.complete(Unit)
                    }
                }
                val media = Media(libVlc, Uri.fromFile(video)).apply {
                    addOption(":no-audio")
                    addOption(":no-sub-autodetect-file")
                    setHWDecoderEnabled(false, false)
                    addOption(":vout=gles2")
                }
                tocador.media = media
                media.release()
                tocador.vlcVout.apply {
                    setVideoSurface(leitor.surface, null)
                    setWindowSize(64, 36)
                    attachViews()
                }
                tocador.play()
            }
            // O vídeo só chega ao fim depois que a saída de vídeo nasceu —
            // e é ao nascer que ela monta o índice.
            withTimeoutOrNull(PRAZO_MS) { fim.await() }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) {
                    runCatching { tocador.setEventListener(null) }
                    runCatching { tocador.vlcVout.detachViews() }
                }
                withContext(Dispatchers.IO) {
                    runCatching { tocador.stop() }
                    runCatching { tocador.release() }
                    runCatching { leitor.close() }
                }
            }
        }
    }
}
