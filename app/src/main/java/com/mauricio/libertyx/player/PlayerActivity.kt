package com.mauricio.libertyx.player

import android.net.Uri
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.view.KeyEvent
import android.view.Gravity
import android.view.MotionEvent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.EscolhaDeFaixas
import com.mauricio.libertyx.core.Historico
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.Pro
import com.mauricio.libertyx.pro.ProActivity
import com.mauricio.libertyx.core.Device
import com.mauricio.libertyx.core.PlayerPreferences
import com.mauricio.libertyx.core.ResumeStore
import com.mauricio.libertyx.rede.AvisoAoServidor
import com.mauricio.libertyx.rede.ContasDeRede
import com.mauricio.libertyx.rede.ServidorDeMidia
import com.mauricio.libertyx.core.TimeFormat
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.databinding.ActivityPlayerBinding
import org.videolan.libvlc.MediaPlayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.random.Random

/**
 * A tela de reprodução e toda a camada de gestos estilo MX Player.
 *
 * Escrita em Views e contra o [VlcEngine], nunca contra o `MediaPlayer` do VLC:
 * os gestos são a parte que dá a cara do app e não podem ser refeitos se o
 * motor mudar. É a mesma separação do `PlayerViewController` do iOS, e ela já
 * se pagou lá — trocar de motor custou uma linha.
 */
/** Nome de episódio: "S01E02", "s1 e2" ou "1x02". */
private val EPISODIO = Regex("""(?i)(\bS\d{1,2}\s?E\d{1,3}\b|\b\d{1,2}x\d{2,3}\b)""")

class PlayerActivity : Activity() {

    /** Ajustes de sensibilidade. */
    private object Tuning {
        /**
         * Teto da rolagem fina: quantos segundos uma largura de tela vale com
         * o dedo devagar. A aceleração multiplica isso quando o dedo corre.
         */
        const val SEEK_SECONDS_PER_SCREEN_WIDTH = 90.0
        /**
         * Quantos dp de arrasto percorrem 0→100% de brilho/volume.
         *
         * Em distância real, e não em fração da tela: deitado a altura é menos
         * da metade da de pé, e a mesma fração fazia o mesmo dedo valer o
         * dobro — o volume ia de ponta a ponta em quatro centímetros. Assim a
         * mão faz o mesmo gesto nas duas posições, e cada nível de volume
         * custa cerca de um centímetro de dedo.
         */
        const val VERTICAL_TRAVEL_DP = 600f
        const val DOUBLE_TAP_SECONDS = 10.0
    }

    private enum class PanAxis { UNDECIDED, HORIZONTAL, VERTICAL, MULTITOUCH }

    private lateinit var ui: ActivityPlayerBinding
    private lateinit var engine: VlcEngine
    /** A superfície escolhida ao abrir: a SurfaceView do HDR, ou a TextureView de sempre. */
    private var superficieDireta = false
    private lateinit var prefs: PlayerPreferences
    private lateinit var audio: AudioManager
    private lateinit var resume: ResumeStore
    private val aviso by lazy { AvisoAoServidor(this) }

    /** O que roda fora da tela enquanto o player está aberto: hoje, as legendas ao lado. */
    private val escopo = MainScope()
    private var buscaDeLegendas: Job? = null

    private val main = Handler(Looper.getMainLooper())

    private var item: MediaItem? = null
    private var playbackSpeed = 1f
    private var rateBeforeHold = 1f
    private var holdActive = false

    private var controlsVisible = true
    private var controlsVisibleSince = 0L
    private var isLocked = false
    private var didPresentError = false
    private var lastSavedPosition = 0.0

    /** Instante a retomar assim que a reprodução começar de fato. */
    private var pendingResume: Double? = null

    private var isBarScrubbing = false
    private var suppressBuffering = false

    // Ferramentas
    private enum class RepeatMode { OFF, ONE }
    private var repeatMode = RepeatMode.OFF
    private var isShuffling = false
    private var rotacao = Rotacao.AUTOMATICA
    private var sleepDeadline: Long? = null
    private val sleepRunnable = Runnable {
        engine.pause()
        sleepDeadline = null
        showHud(getString(R.string.pausado_temporizador), null, null)
        hideHudAfter(2500)
    }

    // Gestos
    private var panAxis = PanAxis.UNDECIDED
    private var panStartX = 0f
    private var panStartY = 0f
    private var panStartTime = 0.0
    private var panStartBrightness = 0.5f
    private var panStartVolume = 0

    /**
     * Destino acumulado do arrasto na tela, e onde o dedo estava no evento
     * anterior — o movimento é somado aos pedaços, cada um com a sua escala.
     */
    private var panSeekTarget = 0.0
    private var panUltimoX = 0f

    /** Mede a rapidez do dedo, que é o que decide a escala do arrasto. */
    private var rastreador: VelocityTracker? = null
    private var panIsOnLeftHalf = true
    private var touchStartedOnBars = false
    private var axisLockThreshold = 12f

    /** Televisão: quem comanda é o controle remoto, e não o dedo. */
    private var isTv = false

    // Ampliação: o enquadramento decide como o vídeo se encaixa na tela; a
    // ampliação é o usuário chegando mais perto de um pedaço. São coisas
    // diferentes e por isso não se atrapalham.
    private var videoZoom = 1f
    private var videoOffsetX = 0f
    private var videoOffsetY = 0f
    private var pinchStartZoom = 1f
    private var pinchStartDistance = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f

    private val gravityModes = listOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT to R.string.ajustar,
        MediaPlayer.ScaleType.SURFACE_FILL to R.string.preencher,
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to R.string.esticar,
    )
    private var gravityIndex = 0

    // MARK: - Ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(ui.root)
        prepararBotaoPular()

        prefs = PlayerPreferences(this)
        resume = ResumeStore.get(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        axisLockThreshold = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        isTv = Device.isTv(this)

        goFullscreen()
        afastarDoRecorte()
        arrumarParaOrientacao()

        // "Abrir com": o vídeo veio de fora e não há lista em volta dele.
        intent?.data?.let { uri ->
            if (intent.action == Intent.ACTION_VIEW) {
                Playback.single(Playback.itemFromUri(this, uri))
            }
        }

        val atual = Playback.current
        if (atual == null) {
            finish()
            return
        }
        // Arquivo do servidor é Pro — e chega aqui por mais de um caminho: a
        // pasta de rede, os recentes da biblioteca e o "abrir com" de outro
        // app. A porta fica no player para valer para todos.
        val daRede = atual.origin is MediaOrigin.Smb || atual.origin is MediaOrigin.WebDav ||
            atual.origin is MediaOrigin.Midia ||
            (atual.origin as? MediaOrigin.Remote)?.uri?.scheme.equals("smb", ignoreCase = true)
        if (daRede && !ProActivity.exigir(this, Pro.Recurso.REDE)) {
            finish()
            return
        }
        item = atual
        // Miniaturas do servidor esperam: disputariam a rede com o filme
        // justamente no começo, quando ele mais precisa dela.
        com.mauricio.libertyx.library.Thumbnails.pausar(true)

        // Vídeo HDR numa tela HDR vai direto para a tela, com o HDR junto (ver Hdr).
        superficieDireta = isTv || (Hdr.telaMostra(this) && Hdr.ehHdr(this, atual))
        engine = VlcEngine(this, superficieDireta)
        engine.attach(ui.videoLayout)

        bindControls()
        if (isTv) prepararParaTv()
        bindEngine()
        updateNavigation()
        loadAndPlay()
    }

    override fun onPause() {
        super.onPause()
        // Ao fechar, o aviso ao servidor sai aqui, e não no onDestroy: esse
        // vem depois de a tela de trás já ter pedido o "Continuar" de novo.
        if (isFinishing && ::engine.isInitialized) encerrar(engine.currentTime)
        passarParaSegundoPlano()
    }

    override fun onResume() {
        super.onResume()
        voltarDoSegundoPlano()
    }

    /** O filme passou para o serviço de áudio, e o player espera a volta. */
    private var emSegundoPlano = false

    /**
     * Apagou a tela com o filme tocando: o som continua.
     *
     * Só com a tela apagada, e não ao sair para a tela inicial — lá quem
     * assume é a janela flutuante, que continua mostrando a imagem. É no
     * `onPause`, e não no `onStop`, porque aqui o app ainda conta como em
     * primeiro plano: depois, o Android 12+ recusa começar o serviço.
     *
     * O serviço é o do Android Auto, que toca só a faixa de áudio: decodificar
     * imagem para uma tela apagada só gastaria bateria.
     */
    private fun passarParaSegundoPlano() {
        if (isTv || isFinishing || isInPip() || !::engine.isInitialized) return
        if (!SegundoPlano.disponivel || !Pro.ativo) return
        if (engine.state != PlaybackState.Playing) return
        val energia = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (energia.isInteractive) return

        val posicao = engine.currentTime
        engine.pause()
        saveResumeNow()
        val pedido = Intent(SegundoPlano.ACAO)
            .setClassName(packageName, SegundoPlano.SERVICO)
            .putExtra(SegundoPlano.EXTRA_POSICAO, posicao)
            .putExtra(SegundoPlano.EXTRA_VELOCIDADE, playbackSpeed)
        SegundoPlano.ultimaPosicao = null
        runCatching { androidx.core.content.ContextCompat.startForegroundService(this, pedido) }
            .onSuccess { emSegundoPlano = true }
            .onFailure { engine.play() }
    }

    /** De volta à tela: o player retoma de onde o áudio chegou. */
    private fun voltarDoSegundoPlano() {
        if (!emSegundoPlano) return
        emSegundoPlano = false

        val continuava = SegundoPlano.ativo
        val posicao = if (continuava) SegundoPlano.posicao() else SegundoPlano.ultimaPosicao
        val tocava = continuava && SegundoPlano.tocando()
        if (continuava) SegundoPlano.parar()

        // Na notificação dá para pular de vídeo; aí o player abre o novo.
        if (Playback.current?.id != item?.id) {
            switchTo(Playback.index)
            return
        }
        // Reabre em vez de só buscar: com a tela apagada a superfície do vídeo
        // foi destruída, e o VLC seguia tocando sem voltar a desenhar — o som
        // andava e a imagem ficava congelada no último quadro.
        val alvo = item ?: return
        engine.load(alvo, posicao).onFailure {
            presentError(it.message ?: getString(R.string.erro_abrir_curto))
            return
        }
        engine.play()
        // Pausado na tela de bloqueio continua pausado — mas só depois do
        // primeiro quadro, senão a tela ficaria preta.
        if (!tocava) main.postDelayed({ engine.pause() }, 600)
    }

    override fun onStop() {
        super.onStop()
        // Sair com a janela flutuante aberta é legítimo: o vídeo continua nela.
        if (isInPip()) return
        if (::engine.isInitialized) engine.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        com.mauricio.libertyx.library.Thumbnails.pausar(false)
        main.removeCallbacksAndMessages(null)
        escopo.cancel()
        if (::engine.isInitialized) {
            encerrar(engine.currentTime)
            engine.detach()
            engine.teardown()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        arrumarParaOrientacao()
        // Sem isto, girar a tela deixa o vídeo com o enquadramento antigo — a
        // imagem fica esticada ou com tarja até a próxima troca de modo.
        ui.videoLayout.post { engine.updateSurfaces() }
    }

    /**
     * Afasta as barras do recorte da câmera e dos gestos do sistema.
     *
     * O vídeo ocupa a tela inteira, recorte incluído — é o que se quer numa
     * imagem. Os **controles**, não: no retrato de um celular com câmera na
     * tela, a barra de cima cai exatamente embaixo dela, e o título fica
     * escondido atrás do furo. Deitado, o recorte muda de lado, por isso o
     * afastamento vem dos quatro lados e não de um número fixo.
     *
     * O recuo de origem é lido uma vez e somado, em vez de substituído: os
     * valores do XML continuam valendo, e o do sistema entra por cima.
     */
    private fun afastarDoRecorte() {
        val topoOriginal = ui.topBar.paddingTop
        // Só no retrato a ilha flutua. Deitada ela vive dentro da fileira de
        // baixo, e mexer na margem dela ali seria empurrar um botão no meio
        // de uma linha — além de estourar na conversão de tipo.
        val ilhaBase = (76 * resources.displayMetrics.density).toInt()
        val baseOriginal = ui.bottomBar.paddingBottom
        val ladoCima = ui.topBar.paddingLeft
        val ladoBaixo = ui.bottomBar.paddingLeft

        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { _, insets ->
            val livre = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars()
            )
            // Um piso de folga além do que o sistema informa: há aparelho que
            // declara recorte zero e mesmo assim tem câmera na tela.
            val piso = (8 * resources.displayMetrics.density).toInt()
            ui.topBar.updatePadding(
                left = ladoCima + livre.left,
                top = topoOriginal + maxOf(livre.top, piso),
                right = ladoCima + livre.right,
            )
            ui.bottomBar.updatePadding(
                left = ladoBaixo + livre.left,
                right = ladoBaixo + livre.right,
                bottom = baseOriginal + livre.bottom,
            )
            // A ilha desce o mesmo tanto que a barra de cima. Com recuo fixo
            // ela caía em cima do título assim que o recorte empurrava a barra
            // para baixo — duas coisas no mesmo lugar, e nenhuma legível.
            // Só quando ela flutua: deitada, a ilha vive dentro da fileira e
            // mexer na margem ali empurraria um botão no meio da linha.
            if (ui.island.layoutParams is FrameLayout.LayoutParams) {
                ui.island.updateLayoutParams<FrameLayout.LayoutParams> {
                    topMargin = ilhaBase + maxOf(livre.top, piso)
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(ui.root)
    }

    /**
     * A mesma tela, arrumada de dois jeitos.
     *
     * Deitado sobra largura e falta altura; em pé é o contrário. Então os
     * botões mudam de lugar em vez de encolher:
     *
     * - **Deitado**, o transporte encosta à esquerda, junto do cadeado — é
     *   onde o polegar esquerdo já está quando se segura o aparelho com as
     *   duas mãos —, e a ilha se dissolve na fileira de baixo, onde há espaço
     *   de sobra. Nada fica flutuando sobre a imagem.
     * - **Em pé**, o transporte volta ao centro e a ilha volta a flutuar,
     *   porque numa fileira estreita não caberia mais nada.
     *
     * Feito movendo as views, e não trocando de layout por qualificador de
     * recurso: a atividade declara `configChanges` para o vídeo não reiniciar
     * ao girar, e por isso o Android nunca reinfla nada — um `layout-land`
     * ficaria no disco sem jamais ser usado.
     */
    private fun arrumarParaOrientacao() {
        // Televisão é sempre deitada — e mesmo que um aparelho relate outra
        // coisa, uma pastilha flutuando no meio da tela a três metros seria
        // pior que a fileira.
        val deitado = isTv || resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

        // Os dois espaçadores decidem onde o transporte fica: com o da
        // esquerda zerado, o grupo encosta na borda.
        ui.spaceLeft.updateLayoutParams<LinearLayout.LayoutParams> {
            weight = if (deitado) 0f else 1f
        }

        val paiAtual = ui.island.parent as? ViewGroup
        val paiDesejado: ViewGroup = if (deitado) ui.buttonRow else ui.root
        if (paiAtual === paiDesejado) return

        paiAtual?.removeView(ui.island)

        if (deitado) {
            ui.island.background = null
            ui.island.setPadding(0, 0, 0, 0)
            // Antes do enquadrar: as ferramentas de faixa ficam juntas, e as
            // de imagem continuam na ponta.
            val posicao = ui.buttonRow.indexOfChild(ui.btnAspect)
            ui.buttonRow.addView(
                ui.island, posicao,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = android.view.Gravity.CENTER_VERTICAL },
            )
        } else {
            ui.island.setBackgroundResource(R.drawable.bg_island)
            val recuo = (6 * resources.displayMetrics.density).toInt()
            ui.island.setPadding(recuo, recuo / 2, recuo, recuo / 2)
            ui.root.addView(
                ui.island,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    topMargin = (76 * resources.displayMetrics.density).toInt()
                },
            )
        }
        ViewCompat.requestApplyInsets(ui.root)
    }

    private fun goFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Deixa a imagem entrar na faixa do recorte em vez de o sistema
        // encolher a janela para fugir dele — quem se afasta são os controles,
        // logo acima, e não o vídeo.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
    }

    // MARK: - Motor

    private fun bindEngine() {
        engine.onTimeUpdate = { tempo ->
            // Aplica a retomada no primeiro sinal de que a reprodução começou
            // DE VERDADE. `play()` marca o estado como tocando na hora, antes
            // de o VLC posicionar o arquivo — buscar ali é ignorado, e o vídeo
            // seguia do zero. Atualização de tempo só chega com ele rodando.
            val alvo = pendingResume
            if (alvo != null && engine.duration > 0) {
                pendingResume = null
                engine.seek(alvo, precise = false)
            } else {
                updateProgress(tempo)
                atualizarPulo(tempo)
                saveResumePoint(tempo)
                aviso.andou(tempo, pausado = false)
            }
        }
        engine.onBufferingChange = { carregando ->
            ui.buffering.visibility =
                if (carregando && !suppressBuffering) View.VISIBLE else View.GONE
        }
        engine.onStateChange = { estado -> applyState(estado) }
        engine.onTracksChange = { /* as listas são lidas na hora de abrir */ }
    }

    private fun applyState(estado: PlaybackState) {
        ui.btnPlay.setImageResource(
            if (estado == PlaybackState.Playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        when (estado) {
            is PlaybackState.Failed -> presentError(estado.message)
            PlaybackState.Ended -> handlePlaybackEnded()
            PlaybackState.Paused -> aviso.andou(engine.currentTime, pausado = true)
            PlaybackState.Playing -> aviso.andou(engine.currentTime, pausado = false)
            else -> {}
        }
    }

    private fun loadAndPlay() {
        val alvo = item ?: return
        ui.tvTitle.text = alvo.title
        didPresentError = false
        lastSavedPosition = 0.0
        encerrado = false
        Historico.get(this).registrar(alvo)

        engine.load(alvo).onFailure {
            presentError(it.message ?: getString(R.string.erro_abrir_curto))
            return
        }
        buscarLegendasAoLado(alvo)
        carregarSegmentos(alvo)

        // Retomar é pergunta, não regra: às vezes se quer rever o filme desde o
        // começo, e voltar sozinho ao meio obriga a desfazer na mão toda vez.
        // Quem já respondeu na página do servidor ("Continuar" ou "Do
        // início") não é perguntado de novo.
        val pedido = EscolhaDeFaixas.consumirInicio(alvo.origin.resumeKey)
        if (pedido == EscolhaDeFaixas.Inicio.DO_COMECO) resume.clear(alvo.origin.resumeKey)
        val retomada = if (pedido == EscolhaDeFaixas.Inicio.DO_COMECO) null else resume.position(alvo.origin.resumeKey)
        aviso.comecou(alvo, retomada ?: 0.0)
        if (retomada != null && prefs.askToResume && pedido != EscolhaDeFaixas.Inicio.CONTINUAR) {
            perguntarRetomada(retomada)
        } else {
            if (retomada != null) pendingResume = retomada
            engine.play()
            scheduleControlsHide()
        }
    }

    /**
     * As legendas em arquivo próprio ao lado do vídeo (ver [LegendasAoLado]).
     * Chegam depois de o vídeo já estar tocando — buscar antes atrasaria a
     * abertura por uma ida ao servidor — e entram sem ser ligadas: quem
     * decide qual fica ligada é a regra da legenda forçada.
     */
    private fun buscarLegendasAoLado(alvo: MediaItem) {
        buscaDeLegendas?.cancel()
        buscaDeLegendas = escopo.launch {
            val arquivos = LegendasAoLado.buscar(applicationContext, alvo.origin)
            // Trocou de vídeo enquanto baixava: estas eram do anterior.
            if (item?.origin != alvo.origin) return@launch
            arquivos.forEach { engine.addSubtitle(Uri.fromFile(it), selecionar = false) }
        }
    }

    // MARK: - Pular abertura

    /** Abertura, créditos e recapitulação marcados no Jellyfin (ver [ServidorDeMidia.segmentos]). */
    private var segmentos: List<ServidorDeMidia.Segmento> = emptyList()
    private var segmentoNaTela: ServidorDeMidia.Segmento? = null
    private lateinit var botaoPular: TextView

    /**
     * O botão claro no canto, por cima de tudo, como no app do Jellyfin e nos
     * de streaming: aparece só enquanto o trecho marcado passa, com ou sem os
     * controles na tela. Na TV ele pega o foco, e o OK pula.
     */
    private fun prepararBotaoPular() {
        val dp = resources.displayMetrics.density
        botaoPular = TextView(this).apply {
            setTextColor(android.graphics.Color.BLACK)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((20 * dp).toInt(), (11 * dp).toInt(), (20 * dp).toInt(), (11 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 30 * dp
                setColor(android.graphics.Color.argb(235, 255, 255, 255))
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_pular, 0, 0, 0)
            compoundDrawablePadding = (8 * dp).toInt()
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { pularSegmento() }
        }
        ui.root.addView(
            botaoPular,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = (28 * dp).toInt()
                bottomMargin = (120 * dp).toInt()
            },
        )
    }

    private fun carregarSegmentos(alvo: MediaItem) {
        segmentos = emptyList()
        mostrarPulo(null)
        val origem = alvo.origin as? MediaOrigin.Midia ?: return
        val loja = ContasDeRede.get(this)
        val conta = loja.byId(origem.contaId) ?: return
        escopo.launch {
            val lidos = ServidorDeMidia(conta, loja.segredo(conta)).segmentos(origem.itemId)
            if (item?.origin != alvo.origin) return@launch
            segmentos = lidos.filter { it.tipo in setOf("Intro", "Outro", "Recap", "Preview") }
        }
    }

    private fun atualizarPulo(tempo: Double) {
        if (segmentos.isEmpty()) return
        // Some no último segundo do trecho: pular dali já não adianta nada.
        val atual = segmentos.firstOrNull { tempo >= it.inicio && tempo < it.fim - 1 }
        if (atual != segmentoNaTela) mostrarPulo(atual)
    }

    private fun mostrarPulo(segmento: ServidorDeMidia.Segmento?) {
        segmentoNaTela = segmento
        if (!::botaoPular.isInitialized) return
        if (segmento == null) {
            botaoPular.visibility = View.GONE
            return
        }
        botaoPular.text = getString(
            when (segmento.tipo) {
                "Outro" -> R.string.pular_creditos
                "Recap" -> R.string.pular_recapitulacao
                "Preview" -> R.string.pular_previa
                else -> R.string.pular_abertura
            }
        )
        botaoPular.visibility = View.VISIBLE
        if (isTv) botaoPular.requestFocus()
    }

    private fun pularSegmento() {
        val segmento = segmentoNaTela ?: return
        mostrarPulo(null)
        // Créditos que vão até o fim levam ao fim — e o fim leva ao próximo episódio.
        val destino = minOf(segmento.fim, maxOf(0.0, engine.duration - 0.5))
        engine.seek(destino, precise = false)
    }

    private fun perguntarRetomada(instante: Double) {
        cancelControlsHide()
        dialog()
            .setTitle(item?.title)
            .setMessage(getString(R.string.voce_parou_em, TimeFormat.clock(instante)))
            .setPositiveButton(getString(R.string.continuar_de, TimeFormat.clock(instante))) { _, _ ->
                // Buscar antes de a reprodução começar é ignorado pelo VLC — ele
                // ainda não tem o arquivo posicionado. A marca fica guardada e é
                // aplicada assim que ele começa a tocar.
                pendingResume = instante
                engine.play()
                scheduleControlsHide()
            }
            .setNegativeButton(getString(R.string.comecar_do_inicio)) { _, _ ->
                item?.let { resume.clear(it.origin.resumeKey) }
                engine.play()
                scheduleControlsHide()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Guarda a posição de tempos em tempos, não a cada quadro: são dezenas de
     * gravações por segundo contra uma a cada cinco segundos.
     */
    private fun saveResumePoint(tempo: Double) {
        val alvo = item ?: return
        if (engine.duration <= 0 || abs(tempo - lastSavedPosition) < 5) return
        lastSavedPosition = tempo
        resume.save(tempo, engine.duration, alvo.origin.resumeKey)
    }

    /** Ao sair, sem o intervalo: é justamente quando a posição precisa estar certa. */
    /** Se o vídeo atual já passou por [encerrar]; volta a `false` a cada vídeo. */
    private var encerrado = false

    /**
     * O vídeo atual sai de cena: acabou, foi trocado ou o player fechou.
     *
     * Visto até o fim — pela regra do Jellyfin, ver [ResumeStore.terminou] —,
     * ele sai do histórico e o próximo episódio entra no lugar; senão, fica a
     * marca para retomar. [acabou]: o vídeo chegou ao fim sozinho, e então
     * conta como visto mesmo sem saber a duração.
     */
    private fun encerrar(posicao: Double, acabou: Boolean = false) {
        val alvo = item ?: return
        if (encerrado) return
        encerrado = true
        val visto = acabou || ResumeStore.terminou(posicao, engine.duration)
        if (visto) {
            resume.clear(alvo.origin.resumeKey)
            // O do servidor vem do "A seguir" dele (ver AvisoAoServidor), que
            // sabe da temporada seguinte; a fila só tem esta.
            val proximo = if (alvo.origin is MediaOrigin.Midia) null else proximoEpisodioDaFila()
            Historico.get(this).terminou(alvo.origin.resumeKey, proximo)
        } else {
            saveResumeNow()
        }
        aviso.parou(posicao, visto)
    }

    /**
     * O vídeo seguinte da fila, quando os dois têm nome de episódio (S01E02,
     * 1x02). Numa pasta de filmes, o seguinte é outro filme — e um filme que
     * ninguém começou não tem por que aparecer no histórico.
     */
    private fun proximoEpisodioDaFila(): MediaItem? {
        val atual = item ?: return null
        if (isShuffling) return null
        val seguinte = Playback.queue.getOrNull(Playback.index + 1) ?: return null
        return seguinte.takeIf { EPISODIO.containsMatchIn(atual.title) && EPISODIO.containsMatchIn(it.title) }
    }

    private fun saveResumeNow() {
        val alvo = item ?: return
        if (engine.duration > 0 && engine.currentTime > 30) {
            resume.save(engine.currentTime, engine.duration, alvo.origin.resumeKey)
        }
    }

    private fun presentError(mensagem: String) {
        // A falha chega por duas vias ao mesmo tempo — o retorno de `load` e a
        // transição para Failed. Sem esta trava viriam dois alertas empilhados.
        if (didPresentError) return
        didPresentError = true
        setControlsVisible(true)
        dialog()
            .setTitle(getString(R.string.nao_deu_tocar))
            .setMessage(mensagem)
            .setPositiveButton(getString(R.string.voltar)) { _, _ -> finish() }
            .show()
    }

    // MARK: - Anterior / próxima

    /**
     * Convenção de player: com o vídeo já andando, "anterior" volta ao começo
     * dele; só nos primeiros segundos é que salta para o arquivo anterior. Sem
     * isso, quem quer reiniciar acaba pulando de faixa sem querer.
     */
    private fun goToPrevious() {
        if (engine.currentTime > 3) {
            engine.seek(0.0, precise = false)
            scheduleControlsHide()
            return
        }
        switchTo(Playback.index - 1)
    }

    private fun goToNext() {
        if (isShuffling && Playback.queue.size > 1) {
            // Sorteia sem repetir o atual — cair no mesmo vídeo não parece
            // aleatório, parece defeito.
            var sorteado = Playback.index
            while (sorteado == Playback.index) sorteado = Random.nextInt(Playback.queue.size)
            switchTo(sorteado)
            return
        }
        switchTo(Playback.index + 1)
    }

    private fun handlePlaybackEnded() {
        if (repeatMode == RepeatMode.ONE) {
            engine.seek(0.0, precise = false)
            engine.play()
            return
        }
        encerrar(engine.duration, acabou = true)

        if (isShuffling && Playback.queue.size > 1) goToNext()
        else if (Playback.index + 1 < Playback.queue.size) goToNext()
        else finish()
    }

    private fun switchTo(indice: Int) {
        if (indice !in Playback.queue.indices) return
        encerrar(engine.currentTime)

        Playback.index = indice
        item = Playback.queue[indice]

        // A ampliação era do vídeo anterior; o novo pode nem ter o mesmo
        // formato de tela.
        videoZoom = 1f
        videoOffsetX = 0f
        videoOffsetY = 0f
        applyZoom()

        setControlsVisible(true)
        updateNavigation()
        showHud(item?.title ?: "", null, null)
        hideHudAfter(1200)

        loadAndPlay()
    }

    private fun updateNavigation() {
        ui.btnPrev.isEnabled = Playback.index > 0
        ui.btnNext.isEnabled = Playback.index + 1 < Playback.queue.size
        ui.btnPrev.alpha = if (ui.btnPrev.isEnabled) 1f else 0.35f
        ui.btnNext.alpha = if (ui.btnNext.isEnabled) 1f else 0.35f
    }

    private fun togglePlayPause() {
        if (engine.state == PlaybackState.Playing) {
            engine.pause()
        } else {
            engine.play()
            // `play()` volta para 1×; sem isto a velocidade escolhida some a
            // cada pausa.
            if (playbackSpeed != 1f) engine.rate = playbackSpeed
        }
        scheduleControlsHide()
    }

    private fun jump(delta: Double) {
        val alvo = (engine.currentTime + delta).coerceIn(0.0, max(engine.duration, 0.0))
        mostrarSalto(delta)
        engine.seek(alvo, precise = false)
    }

    // MARK: - Avisos sem caixa

    /** Quanto os toques seguidos já somaram, e quando foi o último. */
    private var saltoSomado = 0.0
    private var saltoEm = 0L

    /**
     * O aviso do toque duplo, do lado em que o dedo tocou.
     *
     * Toques seguidos somam: três toques mostram "+30 s", e não o mesmo "+10 s"
     * piscando três vezes — que não dizia quanto já se tinha andado.
     */
    private fun mostrarSalto(delta: Double) {
        val agora = android.os.SystemClock.uptimeMillis()
        val continua = saltoSomado != 0.0 && sign(saltoSomado) == sign(delta) && agora - saltoEm < 900
        saltoSomado = if (continua) saltoSomado + delta else delta
        saltoEm = agora

        val frente = saltoSomado > 0
        val vista = if (frente) ui.saltoDir else ui.saltoEsq
        (if (frente) ui.saltoEsq else ui.saltoDir).visibility = View.GONE
        (if (frente) ui.saltoDirTexto else ui.saltoEsqTexto).text =
            getString(R.string.salto_s, if (frente) "+" else "−", abs(saltoSomado).toInt())

        vista.animate().cancel()
        if (vista.visibility != View.VISIBLE) {
            vista.alpha = 0f
            vista.scaleX = 0.8f
            vista.scaleY = 0.8f
            vista.visibility = View.VISIBLE
        }
        vista.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()

        main.removeCallbacks(esconderSalto)
        main.postDelayed(esconderSalto, 750)
    }

    private val esconderSalto = Runnable {
        saltoSomado = 0.0
        for (vista in listOf(ui.saltoDir, ui.saltoEsq)) {
            if (vista.visibility != View.VISIBLE) continue
            vista.animate().alpha(0f).setDuration(240)
                .withEndAction { vista.visibility = View.GONE }.start()
        }
    }

    /** O aviso de segurar, no meio da borda direita: a velocidade em dourado. */
    private fun mostrarAceleracao() {
        main.removeCallbacks(esconderSalto)
        saltoSomado = 0.0
        val vista = ui.saltoDir
        ui.saltoEsq.visibility = View.GONE
        ui.saltoDirTexto.text = textoDaAceleracao()
        vista.animate().cancel()
        vista.alpha = 0f
        vista.scaleX = 0.8f
        vista.scaleY = 0.8f
        vista.visibility = View.VISIBLE
        vista.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun textoDaAceleracao(): CharSequence =
        SpannableStringBuilder(velocidadeEmTexto(prefs.holdSpeed)).apply {
            setSpan(
                ForegroundColorSpan(getColor(R.color.libertyx_accent)),
                0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

    private fun esconderAceleracao() {
        main.removeCallbacks(esconderSalto)
        main.post(esconderSalto)
    }

    // MARK: - Controles

    /**
     * Um diálogo escuro.
     *
     * O padrão do sistema é claro, e um retângulo branco no meio de um filme no
     * escuro é um flash na cara — literalmente a queixa que faz gente desistir
     * de mexer nos ajustes durante o vídeo.
     */
    private fun dialog(): AlertDialog.Builder =
        AlertDialog.Builder(this, R.style.Theme_LibertyX_Dialog)

    private fun bindControls() {
        ui.btnClose.setOnClickListener { finish() }
        ui.btnPlay.setOnClickListener { togglePlayPause() }
        ui.btnPrev.setOnClickListener { goToPrevious() }
        ui.btnNext.setOnClickListener { goToNext() }
        ui.btnAspect.setOnClickListener { cycleAspect() }
        ui.btnPip.setOnClickListener { if (ProActivity.exigir(this, Pro.Recurso.JANELA)) enterPip() }
        ui.btnMore.setOnClickListener { showToolsSheet() }
        ui.btnMudo.setOnClickListener { alternarMudo(false) }
        // O balão acompanha a ilha e a barra de cima enquanto está na tela.
        // No começo do filme ele é mostrado junto com os controles, antes de
        // a ilha descer pelo recorte da câmera — medido naquela hora, ficava
        // por baixo dela até o próximo gesto.
        ui.root.viewTreeObserver.addOnGlobalLayoutListener {
            if (ui.hud.visibility == View.VISIBLE) posicionarHud(soDescer = true)
        }
        ui.btnLock.setOnClickListener { setLocked(true) }
        ui.btnUnlock.setOnClickListener { setLocked(false) }

        // A ilha: o que se mexe durante o filme, a um toque.
        ui.btnAudio.setOnClickListener { showTracks(audio = true) }
        ui.btnSubtitle.setOnClickListener { showTracks(audio = false) }
        ui.btnRepeat.setOnClickListener { alternarRepeticao() }
        ui.btnRotate.setOnClickListener { toggleOrientation() }
        ui.btnSpeed.setOnClickListener { showSpeedSheet() }
        atualizarIlha()

        ui.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, valor: Int, doUsuario: Boolean) {
                if (!doUsuario || engine.duration <= 0) return
                val alvo = engine.duration * valor / bar.max
                ui.tvPosition.text = TimeFormat.clock(alvo)
                // Com o dedo na barra, esconder no meio do arrasto é o pior
                // momento possível: o cronômetro só volta a correr ao soltar.
                cancelControlsHide()
                engine.scrub(alvo, prefs.preciseScrub)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                isBarScrubbing = true
                suppressBuffering = true
                ui.buffering.visibility = View.GONE
                cancelControlsHide()
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                if (engine.duration > 0) {
                    engine.seek(engine.duration * bar.progress / bar.max, precise = true)
                }
                isBarScrubbing = false
                suppressBuffering = false
                scheduleControlsHide()
            }
        })

        // O dedo na barra passa por aqui, e não pelo ouvinte acima: a rolagem
        // fina precisa de movimento relativo, que o SeekBar não sabe fazer. As
        // setas do controle remoto continuam no ouvinte.
        ui.seekBar.aoComecar = {
            isBarScrubbing = true
            suppressBuffering = true
            ui.buffering.visibility = View.GONE
            cancelControlsHide()
            engine.beginScrub()
        }
        ui.seekBar.aoArrastar = { valor ->
            if (engine.duration > 0) {
                val alvo = engine.duration * valor / ui.seekBar.max
                ui.tvPosition.text = TimeFormat.clock(alvo)
                engine.scrub(alvo, prefs.preciseScrub)
            }
        }
        ui.seekBar.aoSoltar = {
            engine.endScrub(prefs.preciseScrub)
            isBarScrubbing = false
            suppressBuffering = false
            ui.tvPrecisao.visibility = View.GONE
            scheduleControlsHide()
        }
        ui.seekBar.aoMudarPrecisao = { precisao ->
            if (precisao >= 1f) {
                ui.tvPrecisao.visibility = View.GONE
            } else {
                ui.tvPrecisao.text = when {
                    precisao > 0.4f -> getString(R.string.precisao_meio)
                    precisao > 0.2f -> getString(R.string.precisao_quarto)
                    else -> getString(R.string.precisao_decimo)
                }
                ui.tvPrecisao.visibility = View.VISIBLE
            }
        }

        applyGravity(anunciar = false)
        setControlsVisible(true)
    }

    /**
     * Os ajustes que a televisão exige.
     *
     * Dois botões deixam de fazer sentido sem uma tela sensível ao toque:
     * bloquear a tela protege contra o dedo que encosta sem querer, e não
     * existe dedo aqui; e a janela flutuante do Android não existe em TV.
     * Mantê-los seria oferecer dois caminhos que não levam a lugar nenhum.
     */
    private fun prepararParaTv() {
        // Só sai o que não existe em televisão.
        //
        // Bloquear a tela responde ao dedo que encosta sem querer, e não há
        // dedo aqui. A janela flutuante não existe em TV. E girar a tela é um
        // botão que numa televisão só pode dar errado.
        //
        // As ferramentas de vídeo FICAM: eu as tinha escondido achando que o
        // painel do ⋮ bastava, mas trocar faixa de áudio ou legenda é o tipo de
        // coisa que se faz no meio do filme — dois toques a menos no controle
        // remoto valem mais que a barra enxuta.
        ui.btnLock.visibility = View.GONE
        ui.btnPip.visibility = View.GONE
        ui.btnRotate.visibility = View.GONE
        // A barra fica mais tempo: a três metros, com um controle na mão, a
        // pessoa demora mais para decidir o que apertar do que com o dedo já
        // sobre o botão.
        ui.seekBar.isFocusable = true
        ui.btnPlay.isFocusableInTouchMode = false
        // Subir de qualquer botão de baixo cai na barra, e descer dela volta ao
        // play. Pela geometria o Android escolhia o botão mais perto na
        // diagonal — do play subia para a legenda —, e a barra ficava fora de
        // alcance: para quem segura o controle, buscar pela barra "não
        // funcionava".
        fun subirParaABarra(vista: View) {
            vista.nextFocusUpId = R.id.seek_bar
            if (vista is ViewGroup) for (i in 0 until vista.childCount) subirParaABarra(vista.getChildAt(i))
        }
        listOf(ui.btnPlay, ui.btnAspect).mapNotNull { it.parent as? View }.distinct().forEach(::subirParaABarra)
        ui.seekBar.nextFocusDownId = R.id.btn_play
    }

    /**
     * O controle remoto.
     *
     * A regra que faz os dois mundos conviverem: **com a barra escondida, as
     * setas comandam o vídeo; com a barra à mostra, elas andam entre os
     * botões**. É o que todo player de TV faz, e é o que evita o impasse de
     * uma seta significar duas coisas ao mesmo tempo.
     *
     * As teclas de mídia (as do controle com desenho de play e avanço, e as de
     * qualquer teclado bluetooth) valem sempre, com barra ou sem ela.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlayPause(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { engine.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { engine.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { jump(30.0); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { jump(-30.0); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { goToNext(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { goToPrevious(); return true }
        }

        if (!isTv) return super.dispatchKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            setControlsVisible(true)
            ui.btnMore.post { showToolsSheet() }
            return true
        }

        // Na barra de progresso focada, as setas andam no filme pelo mesmo
        // caminho de quando a barra está escondida — o do próprio SeekBar
        // pulava 5% do filme por toque e nunca confirmava a busca.
        if (controlsVisible && currentFocus === ui.seekBar &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            buscaDoControle(event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, event.repeatCount)
            return true
        }

        // Com os controles na tela, o foco é quem manda: as setas percorrem os
        // botões e o centro aciona o que estiver focado.
        if (controlsVisible) {
            scheduleControlsHide()
            return super.dispatchKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { buscaDoControle(false, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { buscaDoControle(true, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                togglePlayPause()
                mostrarControlesComFoco()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                mostrarControlesComFoco()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // MARK: - Busca pelo controle remoto

    /** Para onde as setas já levaram, ainda sem buscar; `null` fora de uma busca. */
    private var alvoDoControle: Double? = null
    private val confirmarBuscaDoControle = Runnable {
        val alvo = alvoDoControle ?: return@Runnable
        alvoDoControle = null
        isBarScrubbing = false
        engine.seek(alvo, precise = false)
        hideHudAfter(700)
        if (controlsVisible) scheduleControlsHide()
    }

    /**
     * As setas do controle andam no filme como no Jellyfin e no YouTube da TV:
     * cada toque soma ao destino, que aparece na tela, e o vídeo vai até lá de
     * uma vez só, quando as setas param.
     *
     * Antes cada toque era uma busca. Dez toques seguidos eram dez buscas, e o
     * VLC abortava uma para começar a outra — no servidor, a imagem congelava e
     * pulava aos trancos. Segurar a seta acelera: dez segundos por toque,
     * depois meio minuto, depois um minuto por repetição do controle, para que
     * atravessar um filme de duas horas leve segundos e não minutos.
     */
    private fun buscaDoControle(frente: Boolean, repeticao: Int) {
        val duracao = engine.duration
        if (duracao <= 0) return
        val passo = when {
            repeticao == 0 -> Tuning.DOUBLE_TAP_SECONDS
            repeticao < 20 -> 3.0
            repeticao < 60 -> 10.0
            else -> 30.0
        }
        val base = alvoDoControle ?: engine.currentTime
        val alvo = (base + if (frente) passo else -passo).coerceIn(0.0, duracao)
        alvoDoControle = alvo
        isBarScrubbing = true

        val delta = alvo - engine.currentTime
        val sinal = if (delta >= 0) "+" else "−"
        if (controlsVisible) {
            cancelControlsHide()
            ui.seekBar.progress = (alvo / duracao * ui.seekBar.max).toInt()
            ui.tvPosition.text = TimeFormat.clock(alvo)
        } else {
            ui.hudIcon.visibility = View.GONE
            showHud(TimeFormat.clock(alvo), getString(R.string.salto_s, sinal, abs(delta).roundToInt()),
                (alvo / duracao * 100).toInt())
            main.removeCallbacks(hideHudRunnable)
        }
        main.removeCallbacks(confirmarBuscaDoControle)
        main.postDelayed(confirmarBuscaDoControle, 650)
    }

    /**
     * Mostra a barra já com algo focado.
     *
     * Sem isto a barra aparece e a primeira seta não move nada: o foco está em
     * lugar nenhum, e da poltrona isso é indistinguível de um travamento.
     */
    private fun mostrarControlesComFoco() {
        setControlsVisible(true)
        ui.btnPlay.post { ui.btnPlay.requestFocus() }
        scheduleControlsHide()
    }

    /**
     * Na TV, o foco da abertura vai para o play.
     *
     * Sem isto o Android escolhe o primeiro botão da tela — o de voltar, no
     * canto de cima —, e o primeiro OK do controle, que qualquer um aperta
     * para pausar, fechava o filme.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isTv && !focoInicialDado) {
            focoInicialDado = true
            if (controlsVisible) ui.btnPlay.post { ui.btnPlay.requestFocus() }
        }
    }
    private var focoInicialDado = false

    private fun updateProgress(tempo: Double) {
        val duracao = engine.duration
        ui.tvPosition.text = TimeFormat.clock(tempo)
        ui.tvDuration.text = TimeFormat.clock(duracao)
        if (duracao > 0 && !isBarScrubbing) {
            ui.seekBar.progress = (tempo / duracao * ui.seekBar.max).toInt()
            val carregado = engine.bufferedTime
            ui.seekBar.secondaryProgress =
                if (carregado > 0) (carregado / duracao * ui.seekBar.max).toInt() else 0
        }
    }

    private fun setControlsVisible(visivel: Boolean) {
        controlsVisible = visivel
        val alvo = if (visivel) View.VISIBLE else View.GONE
        ui.topBar.visibility = alvo
        ui.bottomBar.visibility = alvo
        // A ilha é parte dos controles: se ficasse fixa, seria a única coisa
        // por cima do filme depois que tudo some. Deitada ela já mora dentro
        // da barra e desapareceria junto de qualquer jeito — manter a regra
        // aqui evita depender de onde ela está.
        ui.island.visibility = if (visivel) View.VISIBLE else View.GONE
        if (visivel) {
            controlsVisibleSince = System.currentTimeMillis()
            goFullscreen()
        }
    }

    private fun scheduleControlsHide() {
        cancelControlsHide()
        // Em "Nunca" a barra fica até o usuário tocar na tela — o agendamento
        // simplesmente não acontece.
        val atraso = prefs.autoHide.delayMillis ?: return
        main.postDelayed(hideControlsRunnable, atraso)
    }

    private fun cancelControlsHide() = main.removeCallbacks(hideControlsRunnable)

    private val hideControlsRunnable = Runnable {
        // Só o pausado segura os controles na tela — ali o usuário quer ver o
        // botão de play.
        if (engine.state == PlaybackState.Paused) return@Runnable
        setControlsVisible(false)
    }

    /**
     * O que a ilha mostra do estado atual.
     *
     * Repetir e velocidade são modos que ficam ligados: sem um sinal, a pessoa
     * liga o repetir, esquece, e três horas depois o filme recomeça sozinho
     * sem explicação. O amarelo é esse sinal.
     */
    private fun atualizarIlha() {
        ui.btnRepeat.setColorFilter(
            if (repeatMode == RepeatMode.ONE) getColor(R.color.libertyx_accent) else android.graphics.Color.WHITE
        )
        ui.btnSpeed.setColorFilter(
            if (playbackSpeed != 1f) getColor(R.color.libertyx_accent) else android.graphics.Color.WHITE
        )
        ui.btnRotate.setColorFilter(
            if (rotacao != Rotacao.AUTOMATICA) getColor(R.color.libertyx_accent) else android.graphics.Color.WHITE
        )
    }

    private fun alternarRepeticao() {
        repeatMode = if (repeatMode == RepeatMode.ONE) RepeatMode.OFF else RepeatMode.ONE
        atualizarIlha()
        showHud(
            getString(if (repeatMode == RepeatMode.ONE) R.string.repetindo else R.string.repeticao_desligada),
            null, null,
        )
        hideHudAfter(1400)
        scheduleControlsHide()
    }

    private fun setLocked(travado: Boolean) {
        isLocked = travado
        setControlsVisible(!travado)
        ui.btnUnlock.visibility = if (travado) View.VISIBLE else View.GONE
        if (travado) {
            cancelControlsHide()
            showHud(getString(R.string.tela_bloqueada), null, null)
            hideHudAfter(1200)
        } else {
            scheduleControlsHide()
        }
    }

    // MARK: - Gestos

    /**
     * Todo toque passa por aqui antes de chegar aos botões.
     *
     * É o equivalente dos reconhecedores de gesto do UIKit, que convivem com os
     * botões da tela. A regra que faz os dois coexistirem: um toque que COMEÇA
     * dentro de uma das barras pertence às barras — do começo ao fim do gesto,
     * senão arrastar na barra de progresso também rolaria o vídeo.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // A ilha entra na conta junto com as barras.
            //
            // Faltava, e o efeito era o pior possível: o toque num botão dela
            // caía na camada de gestos, que responde ao encostar do dedo
            // escondendo os controles. Ou seja, o botão não fazia nada E a
            // ilha sumia — parecia que o toque tinha "passado através" dela.
            touchStartedOnBars =
                (controlsVisible && (
                    dentroDe(ui.topBar, ev) ||
                        dentroDe(ui.bottomBar, ev) ||
                        dentroDe(ui.island, ev)
                    )) ||
                    (isLocked && dentroDe(ui.btnUnlock, ev))
        }

        if (touchStartedOnBars) return super.dispatchTouchEvent(ev)

        // Com a tela bloqueada, todo gesto é ignorado — é para isso que serve.
        if (isLocked) return true

        handleGesture(ev)
        return true
    }

    private fun dentroDe(view: View, ev: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val pos = IntArray(2)
        view.getLocationOnScreen(pos)
        val x = ev.rawX
        val y = ev.rawY
        return x >= pos[0] && x <= pos[0] + view.width && y >= pos[1] && y <= pos[1] + view.height
    }

    private fun handleGesture(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panAxis = PanAxis.UNDECIDED
                panStartX = ev.x
                panStartY = ev.y
                panStartTime = engine.currentTime
                panSeekTarget = panStartTime
                panUltimoX = 0f
                rastreador?.recycle()
                rastreador = VelocityTracker.obtain().apply { addMovement(ev) }
                panStartBrightness = brilhoAtual()
                panStartVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                panIsOnLeftHalf = ev.x < ui.root.width / 2f

                // Mostrar e esconder os controles ficam para o soltar
                // (ACTION_UP), e só num toque curto: encostar também é o
                // começo de segurar e de arrastar.
                toqueFoiDuplo = false

                agendarToqueLongo()
                detectarToqueDuplo(ev)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelarToqueLongo()
                if (ev.pointerCount == 2) {
                    panAxis = PanAxis.MULTITOUCH
                    pinchStartZoom = videoZoom
                    pinchStartDistance = distancia(ev)
                    lastMidX = (ev.getX(0) + ev.getX(1)) / 2
                    lastMidY = (ev.getY(0) + ev.getY(1)) / 2
                    cancelControlsHide()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (panAxis == PanAxis.MULTITOUCH) {
                    if (ev.pointerCount >= 2) handlePinch(ev)
                    return
                }
                rastreador?.addMovement(ev)
                val dx = ev.x - panStartX
                val dy = ev.y - panStartY

                if (panAxis == PanAxis.UNDECIDED) {
                    if (max(abs(dx), abs(dy)) <= axisLockThreshold) return
                    cancelarToqueLongo()
                    // Se o segurar já tinha acelerado, o dedo que começou a
                    // andar mudou de ideia: desfaz o 2× antes de virar busca
                    // ou volume. Quem encosta com cuidado para mirar um tempo
                    // fica parado o bastante para o 2× entrar, e o arrasto
                    // seguinte acontecia com o vídeo correndo.
                    desfazerAceleracao()
                    panAxis = if (abs(dx) > abs(dy)) PanAxis.HORIZONTAL else PanAxis.VERTICAL
                    // Qualquer gesto em curso segura os controles na tela.
                    cancelControlsHide()
                    if (panAxis == PanAxis.HORIZONTAL) {
                        suppressBuffering = true
                        ui.buffering.visibility = View.GONE
                        engine.beginScrub()
                    }
                }

                when (panAxis) {
                    PanAxis.HORIZONTAL -> updateSeekPan(dx, velocidadeDoDedo())
                    PanAxis.VERTICAL -> updateVerticalPan(dy)
                    else -> {}
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelarToqueLongo()
                if (ev.actionMasked == MotionEvent.ACTION_UP && panAxis == PanAxis.UNDECIDED &&
                    !holdActive && !toqueFoiDuplo && ev.eventTime - ev.downTime < 350
                ) {
                    alternarControlesNoToque()
                }
                desfazerAceleracao()
                if (panAxis == PanAxis.HORIZONTAL) {
                    engine.endScrub(prefs.preciseScrub)
                    suppressBuffering = false
                }
                rastreador?.recycle()
                rastreador = null
                if (panAxis == PanAxis.MULTITOUCH) encaixarZoom()
                panAxis = PanAxis.UNDECIDED
                scheduleControlsHide()
                hideHudAfter(700)
            }
        }
    }

    private fun distancia(ev: MotionEvent): Float {
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return kotlin.math.hypot(dx, dy)
    }

    // MARK: - Rolagem horizontal

    /**
     * Arrasto na tela com aceleração.
     *
     * Antes a distância valia sempre o mesmo, e as duas pontas saíam perdendo:
     * devagar era grosseiro demais para parar num minuto, e depressa não ia
     * longe — num filme de duas horas, voltar meia hora pedia quinze arrastos,
     * o que parecia o vídeo "não voltar".
     *
     * Agora a escala depende da rapidez do dedo, como o ponteiro de um
     * computador: devagar, uma largura de tela vale até um minuto e meio, e dá
     * para parar no segundo; correndo, até doze vezes isso. O mesmo gesto leva
     * perto e depois afina, sem soltar o dedo.
     */
    private fun updateSeekPan(dx: Float, rapidez: Float) {
        if (engine.duration <= 0) return

        val passo = dx - panUltimoX
        panUltimoX = dx

        // Vídeo curto não precisa de tanto: um clipe de 40 s não deve
        // atravessar inteiro num milímetro.
        val base = (engine.duration / 20).coerceIn(10.0, Tuning.SEEK_SECONDS_PER_SCREEN_WIDTH)
        val segundosPorPixel = base / max(ui.root.width, 1)
        val fator = (1 + (rapidez - 250) / 250).coerceIn(1f, 12f)

        panSeekTarget = (panSeekTarget + passo * segundosPorPixel * fator)
            .coerceIn(0.0, engine.duration)
        val alvo = panSeekTarget

        showHud(
            TimeFormat.clock(alvo),
            TimeFormat.signed(alvo - panStartTime),
            null,
            legendaDourada = true,
        )
        ui.tvPosition.text = TimeFormat.clock(alvo)
        if (engine.duration > 0) {
            ui.seekBar.progress = (alvo / engine.duration * ui.seekBar.max).toInt()
        }

        // É aqui que mora a "rolagem integral": em vez de só mostrar um rótulo
        // e buscar no fim, mandamos buscas exatas durante o arrasto, com ritmo
        // controlado pelo motor. Sem isso o vídeo pula de keyframe em keyframe,
        // que é a queixa que originou o projeto.
        engine.scrub(alvo, prefs.preciseScrub)
    }

    /**
     * A rapidez do dedo em dp por segundo.
     *
     * Em dp, e não em pixels: a mesma mão num aparelho mais denso geraria um
     * número maior, e a aceleração responderia diferente em cada celular.
     */
    private fun velocidadeDoDedo(): Float {
        val medidor = rastreador ?: return 0f
        medidor.computeCurrentVelocity(1000)
        return abs(medidor.xVelocity) / resources.displayMetrics.density
    }

    /**
     * Volta à velocidade de antes, uma vez só.
     *
     * Pode ser pedido por dois caminhos — soltar o dedo e começar a arrastar —,
     * e restaurar duas vezes gravaria a velocidade errada.
     */
    private fun desfazerAceleracao() {
        if (!holdActive) return
        holdActive = false
        engine.rate = rateBeforeHold
        esconderAceleracao()
    }

    // MARK: - Brilho e volume

    private fun updateVerticalPan(dy: Float) {
        // Desconta o limiar que decidiu o eixo: sem isso o valor já nasce
        // deslocado no instante em que o gesto é reconhecido.
        val andado = dy - axisLockThreshold * sign(dy)
        // Para cima aumenta: invertemos porque dy cresce para baixo.
        val curso = Tuning.VERTICAL_TRAVEL_DP * resources.displayMetrics.density
        val fracao = -andado / curso

        if (panIsOnLeftHalf) {
            val valor = (panStartBrightness + fracao).coerceIn(0.01f, 1f)
            window.attributes = window.attributes.apply { screenBrightness = valor }
            showHud("${(valor * 100).toInt()}%", getString(R.string.brilho), (valor * 100).toInt())
            ui.hudIcon.setImageResource(R.drawable.ic_brightness)
            ui.hudIcon.visibility = View.VISIBLE
        } else {
            val maximo = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            // Arredondar, e não truncar: truncando, descer um décimo de passo
            // já baixava o volume enquanto subir o mesmo tanto não fazia nada,
            // e o gesto respondia diferente para cada lado.
            val valor = (panStartVolume + fracao * maximo).roundToInt().coerceIn(0, maximo)
            // Volume do aparelho, o mesmo dos botões laterais — e sem a régua
            // do sistema por cima, porque o balão do gesto já diz o mesmo.
            desfazerMudo()
            if (valor != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, valor, 0)
            }
            showHud("${valor * 100 / maximo}%", getString(R.string.volume), valor * 100 / maximo)
            ui.hudIcon.setImageResource(R.drawable.ic_volume)
            ui.hudIcon.visibility = View.VISIBLE
        }
    }

    /**
     * O brilho de onde o gesto parte.
     *
     * `screenBrightness` vale -1 enquanto a janela não tiver brilho próprio —
     * ela está seguindo o do sistema. Começar de -1 faria o primeiro arrasto
     * apagar a tela.
     */
    private fun brilhoAtual(): Float {
        val daJanela = window.attributes.screenBrightness
        if (daJanela >= 0) return daJanela
        return runCatching {
            android.provider.Settings.System.getInt(
                contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS
            ) / 255f
        }.getOrDefault(0.5f)
    }

    /** Os botões físicos de volume usam o mesmo balão do gesto. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val passo = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return super.onKeyDown(keyCode, event)
        }
        desfazerMudo()
        val maximo = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val novo = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + passo).coerceIn(0, maximo)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, novo, 0)
        ui.hudIcon.setImageResource(R.drawable.ic_volume)
        ui.hudIcon.visibility = View.VISIBLE
        showHud("${novo * 100 / maximo}%", getString(R.string.volume), novo * 100 / maximo)
        hideHudAfter(1000)
        return true
    }

    // MARK: - Toque duplo e toque longo

    private var lastTapAt = 0L
    private var lastTapX = 0f

    /** O segundo toque de um toque duplo: ao soltar, não alterna os controles. */
    private var toqueFoiDuplo = false

    /**
     * Mostrar e esconder os controles é coisa do toque curto, e de nada mais.
     *
     * Já foi no encostar do dedo: a barra aparecia na hora, sem esperar o
     * toque duplo ser descartado. Mas encostar também é o começo de segurar e
     * de arrastar, e segurar para acelerar enchia a tela de botões em cima do
     * filme que se queria ver correr. Agora é no soltar, e só se o dedo ficou
     * pouco tempo e não andou: segurar mostra só a velocidade, arrastar só o
     * balão do gesto. Continua sem esperar o toque duplo — o soltar chega na
     * hora.
     */
    private fun alternarControlesNoToque() {
        if (controlsVisible) {
            // A margem é para o segundo toque de um toque duplo não desfazer
            // o que o primeiro fez.
            if (System.currentTimeMillis() - controlsVisibleSince > 300) {
                setControlsVisible(false)
            }
        } else {
            setControlsVisible(true)
            scheduleControlsHide()
        }
    }

    private fun detectarToqueDuplo(ev: MotionEvent) {
        val agora = System.currentTimeMillis()
        val intervalo = ViewConfiguration.getDoubleTapTimeout().toLong()
        if (agora - lastTapAt < intervalo && abs(ev.x - lastTapX) < 120) {
            lastTapAt = 0
            toqueFoiDuplo = true
            cancelarToqueLongo()
            val terco = ui.root.width / 3f
            when {
                ev.x < terco -> jump(-Tuning.DOUBLE_TAP_SECONDS)
                ev.x > terco * 2 -> jump(Tuning.DOUBLE_TAP_SECONDS)
                else -> togglePlayPause()
            }
            return
        }
        lastTapAt = agora
        lastTapX = ev.x
    }

    /**
     * Segurar acelera para frente, em qualquer ponto da tela.
     *
     * Já houve voltar acelerado segurando o terço esquerdo; saiu. O VLC não
     * toca de trás para frente, então era pausa e busca em passos — a imagem
     * andava aos trancos, e o lado esquerdo virava armadilha para quem só
     * queria acelerar. Para voltar, o toque duplo e o arrasto já servem.
     */
    private val toqueLongo = Runnable {
        if (engine.state != PlaybackState.Playing) return@Runnable
        holdActive = true
        rateBeforeHold = engine.rate
        engine.rate = prefs.holdSpeed
        // Na tela, só a velocidade: o filme correndo é o que se quer ver.
        if (controlsVisible) setControlsVisible(false)
        mostrarAceleracao()
    }

    private fun agendarToqueLongo() = main.postDelayed(toqueLongo, 350)
    private fun cancelarToqueLongo() = main.removeCallbacks(toqueLongo)

    // MARK: - Ampliação

    /**
     * Pinça amplia e reduz a imagem, como em qualquer foto.
     *
     * Antes (no app de iOS) ela percorria os enquadramentos — mas para isso já
     * existe o botão "Enquadrar", e nenhum outro app do aparelho usa a pinça
     * assim. Dois dedos arrastando movem a imagem ampliada: um dedo já é
     * rolagem e volume, e mexer nisso custaria os gestos principais para servir
     * a um caso ocasional.
     */
    private fun handlePinch(ev: MotionEvent) {
        val distancia = distancia(ev)
        if (pinchStartDistance > 0 && distancia > 0) {
            videoZoom = (pinchStartZoom * distancia / pinchStartDistance).coerceIn(0.5f, 6f)
        }
        val midX = (ev.getX(0) + ev.getX(1)) / 2
        val midY = (ev.getY(0) + ev.getY(1)) / 2
        videoOffsetX += midX - lastMidX
        videoOffsetY += midY - lastMidY
        lastMidX = midX
        lastMidY = midY

        applyZoom()
        showHud("${(videoZoom * 100).toInt()}%", getString(R.string.ampliacao), null)
        cancelControlsHide()
    }

    private fun applyZoom() {
        // A imagem não pode ser arrastada para fora de vista: o limite é a
        // sobra que a ampliação criou de cada lado.
        val sobraX = max(0f, ui.root.width * (videoZoom - 1) / 2)
        val sobraY = max(0f, ui.root.height * (videoZoom - 1) / 2)
        videoOffsetX = videoOffsetX.coerceIn(-sobraX, sobraX)
        videoOffsetY = videoOffsetY.coerceIn(-sobraY, sobraY)

        ui.videoLayout.scaleX = videoZoom
        ui.videoLayout.scaleY = videoZoom
        ui.videoLayout.translationX = videoOffsetX
        ui.videoLayout.translationY = videoOffsetY
    }

    /**
     * Perto do tamanho original a pinça encaixa em 1×: acertar exatamente 100%
     * com dois dedos é impossível, e ficar em 1,03× deixa a imagem tremida sem
     * nenhum motivo.
     */
    private fun encaixarZoom() {
        if (abs(videoZoom - 1f) < 0.08f) {
            videoZoom = 1f
            videoOffsetX = 0f
            videoOffsetY = 0f
            applyZoom()
        }
    }

    private fun resetZoom() {
        videoZoom = 1f
        videoOffsetX = 0f
        videoOffsetY = 0f
        applyZoom()
        showHud("100%", getString(R.string.ampliacao), null)
        hideHudAfter(900)
    }

    // MARK: - Enquadramento

    /**
     * Percorre os enquadramentos e desfaz a ampliação.
     *
     * "Enquadrar" é justamente o botão de "ajeita isso aí": deixar a imagem
     * ampliada por baixo faria o enquadramento novo chegar recortado, e daria a
     * impressão de que o botão não fez nada.
     */
    private fun cycleAspect() {
        gravityIndex = (gravityIndex + 1) % gravityModes.size
        videoZoom = 1f
        videoOffsetX = 0f
        videoOffsetY = 0f
        applyZoom()
        applyGravity()
        scheduleControlsHide()
    }

    private fun applyGravity(anunciar: Boolean = true) {
        val (modo, nome) = gravityModes[gravityIndex]
        engine.setScale(modo)
        if (anunciar) {
            showHud(getString(nome), getString(R.string.enquadramento), null)
            hideHudAfter(900)
        }
    }

    // MARK: - Balão dos gestos

    /**
     * Mostra o balão.
     *
     * Com legenda, são duas linhas de peso diferente: o valor grande em cima
     * (o tempo, o volume) e o que ele é embaixo, pequeno. Antes eram duas
     * linhas iguais, e o olho não sabia qual ler primeiro. Na busca a legenda
     * é o salto, em dourado — é ela que diz "para frente" ou "para trás".
     */
    private fun showHud(titulo: String, legenda: String?, barra: Int?, legendaDourada: Boolean = false) {
        main.removeCallbacks(hideHudRunnable)
        // Já na tela, ele só desce: se os controles somem no meio de um
        // arrasto, o balão subir de repente é pior do que ficar onde está.
        val jaVisivel = ui.hud.visibility == View.VISIBLE
        ui.hud.visibility = View.VISIBLE
        posicionarHud(soDescer = jaVisivel)
        ui.hudText.text = if (legenda == null) {
            titulo
        } else {
            android.text.SpannableStringBuilder().apply {
                append(titulo, android.text.style.RelativeSizeSpan(1.6f), 0)
                append("\n")
                val inicio = length
                append(legenda)
                setSpan(android.text.style.RelativeSizeSpan(0.9f), inicio, length, 0)
                setSpan(
                    android.text.style.ForegroundColorSpan(
                        getColor(if (legendaDourada) R.color.libertyx_accent else R.color.libertyx_muted)
                    ),
                    inicio, length, 0,
                )
            }
        }
        ui.hudText.textAlignment = View.TEXT_ALIGNMENT_CENTER
        if (barra == null) {
            ui.hudBar.visibility = View.GONE
        } else {
            ui.hudBar.visibility = View.VISIBLE
            ui.hudBar.progress = barra
        }
    }

    /**
     * O balão fica abaixo da ilha, sempre.
     *
     * Com recuo fixo ele caía em cima dela assim que o recorte da câmera
     * empurrava a ilha para baixo — o tempo da busca por cima dos botões, e
     * nenhum dos dois legível. A posição é medida a cada exibição porque a
     * ilha muda de lugar: desce com o recorte e, deitado, vai para a fileira
     * de baixo, quando o limite passa a ser a barra de cima.
     */
    private fun posicionarHud(soDescer: Boolean = false) {
        val dp = resources.displayMetrics.density
        var topo = (130 * dp).toInt()
        val params = ui.hud.layoutParams as? FrameLayout.LayoutParams ?: return
        if (soDescer) topo = maxOf(topo, params.topMargin)
        val ilha = ui.island
        if (ilha.visibility == View.VISIBLE && ilha.parent === ui.root && ilha.height > 0) {
            topo = maxOf(topo, ilha.bottom + (14 * dp).toInt())
        }
        if (ui.topBar.visibility == View.VISIBLE && ui.topBar.height > 0) {
            topo = maxOf(topo, ui.topBar.bottom + (14 * dp).toInt())
        }
        if (params.topMargin != topo) {
            params.topMargin = topo
            ui.hud.layoutParams = params
        }
    }

    /**
     * Liga ou desliga o som, e diz isso na tela.
     *
     * O mudo mexe só no volume do VLC, não no do aparelho: a régua do sistema
     * não muda e nada avisava — o filme simplesmente ficava calado. Agora o
     * balão confirma na hora e um alto-falante riscado fica na barra de cima
     * enquanto durar, servindo também de botão para devolver o som.
     */
    private fun alternarMudo(mudo: Boolean) {
        engine.isMuted = mudo
        ui.btnMudo.visibility = if (mudo) View.VISIBLE else View.GONE
        ui.hudIcon.setImageResource(if (mudo) R.drawable.ic_volume_off else R.drawable.ic_volume)
        ui.hudIcon.visibility = View.VISIBLE
        showHud(getString(if (mudo) R.string.sem_som else R.string.som_ligado), null, null)
        hideHudAfter(1400)
    }

    /** Mexer no volume é querer ouvir: o gesto e as teclas desfazem o mudo. */
    private fun desfazerMudo() {
        if (!engine.isMuted) return
        engine.isMuted = false
        ui.btnMudo.visibility = View.GONE
    }

    private fun hideHudAfter(millis: Long) {
        main.removeCallbacks(hideHudRunnable)
        main.postDelayed(hideHudRunnable, millis)
    }

    private val hideHudRunnable = Runnable {
        ui.hud.visibility = View.GONE
        ui.hudIcon.visibility = View.GONE
        ui.hudBar.visibility = View.GONE
    }

    // MARK: - Faixas

    private fun showTracks(audio: Boolean) {
        cancelControlsHide()
        val faixas = if (audio) engine.audioTracks else engine.subtitleTracks
        val atual = if (audio) engine.currentAudioTrack else engine.currentSubtitleTrack

        if (faixas.isEmpty()) {
            Toast.makeText(
                this,
                getString(if (audio) R.string.so_uma_faixa else R.string.sem_legendas),
                Toast.LENGTH_SHORT,
            ).show()
            scheduleControlsHide()
            return
        }

        val rotulos = mutableListOf<String>()
        val ids = mutableListOf<Int?>()
        if (!audio) {
            rotulos += (if (atual == null) "✓ " else "") + getString(R.string.desligada)
            ids += null
        }
        for (faixa in faixas) {
            rotulos += (if (faixa.id == atual) "✓ " else "") + faixa.label
            ids += faixa.id
        }

        dialog()
            .setTitle(getString(if (audio) R.string.faixas_audio else R.string.legendas))
            .setItems(rotulos.toTypedArray()) { _, i ->
                val id = ids[i]
                if (audio) id?.let { engine.selectAudioTrack(it) }
                else engine.selectSubtitleTrack(id)
                // Sem isto a barra ficava presa na tela depois de trocar de
                // faixa: abrir a lista cancela o agendamento e nada o repunha.
                scheduleControlsHide()
            }
            .setOnDismissListener { scheduleControlsHide() }
            .show()
    }

    // MARK: - Menu de ferramentas

    /** "2×" em vez de "2.0×": o zero à direita não diz nada e polui a placa. */
    private fun velocidadeEmTexto(valor: Float): String =
        if (valor % 1f == 0f) "${valor.toInt()}×"
        else String.format(java.util.Locale.getDefault(), "%.1f×", valor)

    /** Uma ferramenta do painel. */
    private data class Ferramenta(
        val rotulo: String,
        val icone: Int,
        /**
         * Em que ponto ela está, quando tem um: "1.5×", "deitado", "10 s".
         *
         * À mostra na placa de propósito: sem isso, saber a velocidade em que
         * o filme está exigia abrir a ferramenta — e o painel existe
         * justamente para não precisar abrir nada para olhar.
         */
        val valor: String? = null,
        /** Modo ligado: o anel fica dourado e ganha o ponto, para não se esquecer de um estado. */
        val aceso: Boolean = false,
        /**
         * Texto que vai dentro do anel no lugar do ícone — a velocidade.
         * "1,5×" diz mais que qualquer desenho de velocímetro.
         */
        val noAnel: String? = null,
        /** Do Pro: depois do teste, leva à tela de compra e ganha o selo. */
        val pro: Boolean = false,
        val acao: () -> Unit,
    )

    /**
     * O painel de ferramentas, no lugar do menu de lista.
     *
     * Dezesseis itens em lista de texto é uma coluna que não cabe na tela e que
     * se lê linha a linha, no escuro, com o filme parado. Em grade de quatro,
     * tudo aparece de uma vez e o dedo vai direto ao desenho — e o rótulo
     * embaixo resolve o que o desenho sozinho não diria ("taxa de proporção"
     * não tem ícone óbvio em lugar nenhum).
     */
    private fun showToolsSheet() {
        cancelControlsHide()

        // Uma grade só, na ordem de uso: o que se procura no meio do filme —
        // faixa, legenda, proporção, velocidade — vem primeiro, onde o polegar
        // já está. Os grupos com título que houve aqui antes pediam leitura; a
        // grade corrida pede só um olhar.
        val itens = buildList {
            add(Ferramenta(getString(R.string.t_faixa_audio), R.drawable.ic_audio_track) { showTracks(audio = true) })
            add(Ferramenta(getString(R.string.t_legenda), R.drawable.ic_subtitle) { showTracks(audio = false) })
            val atraso = engine.subtitleDelay
            add(
                Ferramenta(
                    getString(R.string.t_atraso_legenda), R.drawable.ic_subtitle,
                    valor = if (abs(atraso) >= 0.05) atrasoEmTexto(atraso) else null,
                    aceso = abs(atraso) >= 0.05,
                ) {
                    // Depois de o painel terminar de fechar: o fechamento dele
                    // devolve os controles à tela, e a barra os quer fora.
                    ui.root.post { showSubtitleDelay() }
                }
            )
            val tamanho = TamanhoDaLegenda.atual(this@PlayerActivity)
            add(
                Ferramenta(
                    getString(R.string.t_tamanho_legenda), R.drawable.ic_subtitle,
                    valor = if (tamanho != 100) "$tamanho%" else null, aceso = tamanho != 100,
                ) { ui.root.post { mostrarTamanhoDaLegenda() } }
            )
            add(Ferramenta(getString(R.string.t_proporcao), R.drawable.ic_aspect) { cycleAspect() })
            add(
                Ferramenta(
                    getString(R.string.t_velocidade), R.drawable.ic_speed,
                    aceso = playbackSpeed != 1f, noAnel = velocidadeEmTexto(playbackSpeed),
                ) { showSpeedSheet() }
            )
            add(
                Ferramenta(
                    getString(R.string.t_acelerar_segurar), R.drawable.ic_speed,
                    noAnel = velocidadeEmTexto(prefs.holdSpeed),
                ) { showHoldSpeedSheet() }
            )
            add(Ferramenta(getString(R.string.t_repetir), R.drawable.ic_repeat, aceso = repeatMode == RepeatMode.ONE) {
                alternarRepeticao()
            })
            if (Playback.queue.size > 1) {
                add(Ferramenta(getString(R.string.t_aleatorio), R.drawable.ic_shuffle, aceso = isShuffling) {
                    isShuffling = !isShuffling
                })
            }
            add(Ferramenta(getString(R.string.t_mudo), R.drawable.ic_volume_off, aceso = engine.isMuted) {
                alternarMudo(!engine.isMuted)
            })
            add(Ferramenta(getString(R.string.t_modo_noturno), R.drawable.ic_moon, aceso = ui.dimView.alpha > 0.01f) {
                cycleNightMode()
            })
            add(Ferramenta(getString(R.string.t_captura), R.drawable.ic_camera, pro = true) {
                if (ProActivity.exigir(this@PlayerActivity, Pro.Recurso.CAPTURA)) takeSnapshot()
            })
            // "Bloquear tela" não entra: o cadeado é botão fixo da barra de
            // baixo, e a mesma ação em dois lugares faz parar para escolher
            // entre coisas iguais.
            if (!isTv) {
                add(Ferramenta(getString(R.string.t_janela), R.drawable.ic_pip, pro = true) {
                    if (ProActivity.exigir(this@PlayerActivity, Pro.Recurso.JANELA)) enterPip()
                })
                add(
                    Ferramenta(
                        getString(R.string.t_girar),
                        R.drawable.ic_rotate,
                        valor = when (rotacao) {
                            Rotacao.AUTOMATICA -> getString(R.string.rot_auto)
                            Rotacao.PAISAGEM -> getString(R.string.rot_deitado)
                            Rotacao.RETRATO -> getString(R.string.rot_em_pe)
                        },
                        aceso = rotacao != Rotacao.AUTOMATICA,
                    ) { toggleOrientation() }
                )
            }
            add(Ferramenta(getString(R.string.t_ampliacao_normal), R.drawable.ic_zoom) { resetZoom() })
            add(Ferramenta(getString(R.string.t_dormir), R.drawable.ic_timer, aceso = sleepDeadline != null, pro = true) {
                if (ProActivity.exigir(this@PlayerActivity, Pro.Recurso.DORMIR)) showSleepSheet()
            })
            add(Ferramenta(getString(R.string.t_ocultar_barra), R.drawable.ic_timer, valor = prefs.autoHide.title) {
                showAutoHideSheet()
            })
            add(Ferramenta(getString(R.string.t_quadro_a_quadro), R.drawable.ic_frame, aceso = prefs.preciseScrub) {
                prefs.preciseScrub = !prefs.preciseScrub
                showHud(
                    getString(if (prefs.preciseScrub) R.string.t_quadro_a_quadro else R.string.por_keyframe),
                    getString(R.string.rolagem), null,
                )
                hideHudAfter(1400)
            })
        }

        val vista = layoutInflater.inflate(R.layout.dialog_tools, null)
        val grade = vista.findViewById<GridLayout>(R.id.tools_grid)
        val painel = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val dourado = getColor(R.color.libertyx_accent)

        for (ferramenta in itens) {
            val item = layoutInflater.inflate(R.layout.item_tool, grade, false)
            val icone = item.findViewById<ImageView>(R.id.tool_icon)
            val noAnel = item.findViewById<TextView>(R.id.tool_ring_text)
            val ponto = item.findViewById<View>(R.id.tool_dot)
            val rotulo = item.findViewById<TextView>(R.id.tool_label)

            if (ferramenta.noAnel != null) {
                icone.visibility = View.GONE
                noAnel.visibility = View.VISIBLE
                noAnel.text = ferramenta.noAnel
                noAnel.setTextColor(if (ferramenta.aceso) dourado else android.graphics.Color.WHITE)
            } else {
                icone.setImageResource(ferramenta.icone)
                icone.setColorFilter(if (ferramenta.aceso) dourado else android.graphics.Color.WHITE)
            }
            // O ponto no canto do anel: o estado ligado se vê de longe, sem
            // depender só da cor.
            ponto.visibility = if (ferramenta.aceso) View.VISIBLE else View.GONE
            // O selo só depois do teste: durante ele tudo funciona, e marcar o
            // que um dia vai trancar seria cobrar antes da hora.
            val bloqueada = ferramenta.pro && !Pro.ativo
            item.findViewById<View>(R.id.tool_pro).visibility = if (bloqueada) View.VISIBLE else View.GONE
            if (bloqueada) icone.alpha = 0.55f
            item.isSelected = ferramenta.aceso

            // O valor, quando existe, é a segunda linha do nome — em dourado e
            // um pouco menor. Fica no espaço que o nome já reserva, e a
            // fileira não cresce.
            rotulo.text = ferramenta.valor?.let { valor ->
                SpannableStringBuilder(ferramenta.rotulo).append("\n").apply {
                    val de = length
                    append(valor)
                    setSpan(ForegroundColorSpan(dourado), de, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(0.9f), de, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } ?: ferramenta.rotulo

            item.setOnClickListener {
                painel.dismiss()
                ferramenta.acao()
            }
            grade.addView(
                item,
                GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                }
            )
        }
        // Células vazias fecham a última fileira: sem elas, uma fileira de três
        // repartiria a largura entre três e sairia desalinhada das de cima.
        repeat((4 - itens.size % 4) % 4) {
            grade.addView(
                View(this),
                GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                }
            )
        }

        // O painel não pode passar da tela.
        //
        // Com altura livre, a última fileira ficava cortada pela borda — e
        // fileira cortada não se lê como "role para ver mais", se lê como
        // defeito. Acima do teto, ele rola.
        val rolagem = vista.findViewById<ScrollView>(R.id.tools_scroll)
        rolagem.post {
            val teto = (resources.displayMetrics.heightPixels * 0.60f).toInt()
            if (rolagem.height > teto) {
                rolagem.updateLayoutParams<ViewGroup.LayoutParams> { height = teto }
            }
        }

        painel.setContentView(vista)
        painel.window?.apply {
            // Largura com teto: numa tela de tablet (ou celular deitado) os
            // quatro anéis por fileira ficavam a um palmo um do outro, e o
            // olho tinha de varrer a tela inteira para achar uma ferramenta.
            val teto = (760 * resources.displayMetrics.density).toInt()
            setLayout(
                if (resources.displayMetrics.widthPixels > teto) teto else WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            // O véu da janela é o que separa os anéis do filme agora que a folha
            // é transparente: escurece a cena por igual sem escondê-la. O tema
            // translúcido não liga o véu sozinho — sem a flag, a quantidade
            // acima não fazia nada.
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            setBackgroundDrawableResource(android.R.color.transparent)
            // Sem foco enquanto sobe: uma janela que toma o foco faz o sistema
            // devolver as barras de status e navegação, e o filme atrás salta
            // de tamanho. O foco entra depois que ela já está na tela.
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            decorView.systemUiVisibility = window.decorView.systemUiVisibility
        }
        // Tocar fora fecha, igual ao botão voltar do aparelho.
        //
        // Não vem de graça: o tema translúcido não liga o fechamento por toque
        // externo, e sem isto o painel só saía pelo botão voltar — que é o
        // caminho que ninguém tenta primeiro.
        painel.setCanceledOnTouchOutside(true)
        painel.setCancelable(true)
        // Os controles do player saem enquanto o painel está aberto: com a
        // folha transparente, a barra de baixo aparecia atrás da última
        // fileira e os nomes se embolavam com o relógio e o play.
        setControlsVisible(false)
        painel.setOnDismissListener {
            setControlsVisible(true)
            scheduleControlsHide()
        }
        painel.show()
        painel.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    /** "+1,5 s", "−0,3 s", "0 s": o sinal sempre à mostra, que é o que diz o sentido. */
    private fun atrasoEmTexto(segundos: Double): String = when {
        abs(segundos) < 0.05 -> "0 s"
        else -> String.format(java.util.Locale.getDefault(), "%s%.1f s", if (segundos > 0) "+" else "−", abs(segundos))
    }

    /**
     * O atraso da legenda, como no Jellyfin: uma barra no alto da tela, sem
     * escurecer o filme, que vale na hora enquanto se arrasta.
     *
     * Ajusta-se olhando a boca de quem fala e a legenda ao mesmo tempo, então
     * nada pode cobrir a cena — nem os controles do player, que saem. Os
     * botões − e + andam um décimo de segundo, o passo que o dedo na barra não
     * acerta; na TV a barra já chega focada, e as setas fazem o mesmo.
     */
    private fun showSubtitleDelay() {
        cancelControlsHide()
        setControlsVisible(false)

        val vista = layoutInflater.inflate(R.layout.dialog_atraso_legenda, null)
        val valor = vista.findViewById<TextView>(R.id.atraso_valor)
        val barra = vista.findViewById<SeekBar>(R.id.atraso_barra)
        // Passos de um décimo, de −30 a +30 s: legenda de outra edição do
        // mesmo filme chega a vir dezenas de segundos fora.
        barra.max = (ATRASO_MAXIMO * 20).toInt()
        barra.keyProgressIncrement = 1
        barra.progress = ((engine.subtitleDelay + ATRASO_MAXIMO) * 10).roundToInt().coerceIn(0, barra.max)
        valor.text = atrasoEmTexto(engine.subtitleDelay)
        barra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val segundos = progress / 10.0 - ATRASO_MAXIMO
                engine.subtitleDelay = segundos
                valor.text = atrasoEmTexto(segundos)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        vista.findViewById<View>(R.id.atraso_menos).setOnClickListener { barra.progress -= 1 }
        vista.findViewById<View>(R.id.atraso_mais).setOnClickListener { barra.progress += 1 }

        val janela = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        vista.findViewById<View>(R.id.atraso_fechar).setOnClickListener { janela.dismiss() }
        janela.setContentView(vista)
        janela.window?.apply {
            val teto = (560 * resources.displayMetrics.density).toInt()
            val margem = (16 * resources.displayMetrics.density).toInt()
            setLayout(
                minOf(teto, resources.displayMetrics.widthPixels - 2 * margem),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = margem * 2 }
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            // Como no painel de ferramentas: sem foco enquanto sobe, para o
            // sistema não devolver as barras e o filme não saltar de tamanho.
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            decorView.systemUiVisibility = window.decorView.systemUiVisibility
        }
        janela.setCanceledOnTouchOutside(true)
        janela.setOnDismissListener { scheduleControlsHide() }
        janela.show()
        janela.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // Com a janela já focável — antes disso o pedido se perdia, e as setas
        // andavam entre os botões em vez de mexer na barra.
        if (isTv) {
            barra.isFocusableInTouchMode = true
            barra.postDelayed({ barra.requestFocus() }, 150)
        }
    }

    /**
     * O tamanho da legenda: a mesma barra do atraso, no alto, com − e +, e a
     * legenda à vista embaixo para ver se ficou confortável.
     *
     * O VLC 3 só lê o tamanho quando o motor nasce, então cada mudança refaz
     * o motor no mesmo ponto, com o mesmo áudio e a mesma legenda — o filme
     * pisca e volta. Os toques seguidos esperam meio segundo e viram um só.
     */
    private fun mostrarTamanhoDaLegenda() {
        cancelControlsHide()
        setControlsVisible(false)

        val opcoes = TamanhoDaLegenda.opcoes
        val vista = layoutInflater.inflate(R.layout.dialog_atraso_legenda, null)
        val valor = vista.findViewById<TextView>(R.id.atraso_valor)
        val barra = vista.findViewById<SeekBar>(R.id.atraso_barra)
        vista.findViewById<TextView>(R.id.atraso_dica).setText(R.string.tamanho_legenda_dica)
        barra.max = opcoes.lastIndex
        barra.keyProgressIncrement = 1
        val inicial = TamanhoDaLegenda.atual(this)
        barra.progress = opcoes.indexOf(inicial).takeIf { it >= 0 } ?: opcoes.indexOf(100)
        valor.text = getString(R.string.tamanho_legenda_valor, inicial)
        val refazer = Runnable { refazerMotorComTamanhoDaLegenda() }
        barra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val escala = opcoes[progress]
                valor.text = getString(R.string.tamanho_legenda_valor, escala)
                TamanhoDaLegenda.definir(this@PlayerActivity, escala)
                barra.removeCallbacks(refazer)
                barra.postDelayed(refazer, 500)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        vista.findViewById<View>(R.id.atraso_menos).setOnClickListener { barra.progress -= 1 }
        vista.findViewById<View>(R.id.atraso_mais).setOnClickListener { barra.progress += 1 }

        val janela = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        vista.findViewById<View>(R.id.atraso_fechar).setOnClickListener { janela.dismiss() }
        janela.setContentView(vista)
        janela.window?.apply {
            val teto = (560 * resources.displayMetrics.density).toInt()
            val margem = (16 * resources.displayMetrics.density).toInt()
            setLayout(
                minOf(teto, resources.displayMetrics.widthPixels - 2 * margem),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = margem * 2 }
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            decorView.systemUiVisibility = window.decorView.systemUiVisibility
        }
        janela.setCanceledOnTouchOutside(true)
        janela.setOnDismissListener { scheduleControlsHide() }
        janela.show()
        janela.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (isTv) {
            barra.isFocusableInTouchMode = true
            barra.postDelayed({ barra.requestFocus() }, 150)
        }
    }

    /** Refaz o motor com o tamanho de legenda novo, no mesmo ponto e com as mesmas faixas. */
    private fun refazerMotorComTamanhoDaLegenda() {
        val alvo = item ?: return
        if (!::engine.isInitialized || isFinishing) return
        val instante = engine.currentTime
        val audio = engine.currentAudioTrack
        val legenda = engine.currentSubtitleTrack
        val atraso = engine.subtitleDelay
        val mudo = engine.isMuted
        engine.teardown()
        engine = VlcEngine(this, superficieDireta)
        engine.attach(ui.videoLayout)
        bindEngine()
        engine.load(alvo, instante).onFailure {
            presentError(it.message ?: getString(R.string.erro_abrir_curto))
            return
        }
        engine.restaurarFaixas(audio, legenda)
        engine.subtitleDelay = atraso
        engine.isMuted = mudo
        applyGravity(anunciar = false)
        buscarLegendasAoLado(alvo)
        engine.play()
        if (playbackSpeed != 1f) engine.rate = playbackSpeed
    }

    private fun showSpeedSheet() {
        val valores = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val rotulos = valores.map {
            (if (abs(it - playbackSpeed) < 0.01f) "✓ " else "") + velocidadeEmTexto(it)
        }
        dialog()
            .setTitle(getString(R.string.t_velocidade))
            .setItems(rotulos.toTypedArray()) { _, i ->
                playbackSpeed = valores[i]
                if (engine.state == PlaybackState.Playing) engine.rate = playbackSpeed
                atualizarIlha()
                showHud(velocidadeEmTexto(playbackSpeed), getString(R.string.t_velocidade), null)
                hideHudAfter(900)
            }
            .setOnDismissListener { scheduleControlsHide() }
            .show()
    }

    private fun showHoldSpeedSheet() {
        val opcoes = PlayerPreferences.holdSpeedOptions
        val rotulos = opcoes.map {
            (if (abs(it - prefs.holdSpeed) < 0.01f) "✓ " else "") + velocidadeEmTexto(it)
        }
        dialog()
            .setTitle(getString(R.string.segurar_para_acelerar))
            .setItems(rotulos.toTypedArray()) { _, i -> prefs.holdSpeed = opcoes[i] }
            .setOnDismissListener { scheduleControlsHide() }
            .show()
    }

    private fun showAutoHideSheet() {
        val opcoes = PlayerPreferences.AutoHide.entries
        val rotulos = opcoes.map {
            (if (it == prefs.autoHide) "✓ " else "") + it.title
        }
        dialog()
            .setTitle(getString(R.string.ocultar_barra_depois))
            .setItems(rotulos.toTypedArray()) { _, i ->
                prefs.autoHide = opcoes[i]
                // Reagenda já com o valor novo, para o efeito ser sentido nesta
                // mesma vez em vez de só no próximo toque.
                scheduleControlsHide()
            }
            .setOnDismissListener { scheduleControlsHide() }
            .show()
    }

    /**
     * Percorre os níveis em vez de abrir um menu: uma ferramenta de um toque
     * só, que é o ponto de ela existir.
     */
    private fun cycleNightMode() {
        val niveis = listOf(0f, 0.25f, 0.45f, 0.65f)
        val atual = niveis.indexOfFirst { abs(it - ui.dimView.alpha) < 0.01f }.coerceAtLeast(0)
        val proximo = niveis[(atual + 1) % niveis.size]
        ui.dimView.animate().alpha(proximo).setDuration(200).start()
        showHud(
            if (proximo == 0f) getString(R.string.desligado) else "${(proximo * 100).toInt()}%",
            getString(R.string.t_modo_noturno), null,
        )
        hideHudAfter(1200)
    }

    private fun sleepTimerTitle(): String {
        val prazo = sleepDeadline ?: return getString(R.string.t_dormir)
        val restante = max(0, prazo - System.currentTimeMillis())
        return getString(R.string.dormir_em, (restante / 60000 + 1).toInt())
    }

    private fun showSleepSheet() {
        val minutos = listOf(15, 30, 45, 60)
        val rotulos = buildList {
            add((if (sleepDeadline == null) "✓ " else "") + getString(R.string.desligado))
            addAll(minutos.map { resources.getQuantityString(R.plurals.n_minutos, it, it) })
            add(getString(R.string.no_fim_do_video))
        }
        dialog()
            .setTitle(sleepTimerTitle())
            .setItems(rotulos.toTypedArray()) { _, i ->
                main.removeCallbacks(sleepRunnable)
                when (i) {
                    0 -> {
                        sleepDeadline = null
                        showHud(getString(R.string.desligado), getString(R.string.temporizador), null)
                    }
                    rotulos.lastIndex -> {
                        val restante = max(1.0, engine.duration - engine.currentTime)
                        armarSono((restante * 1000).toLong())
                    }
                    else -> armarSono(minutos[i - 1] * 60_000L)
                }
                hideHudAfter(1800)
            }
            .setOnDismissListener { scheduleControlsHide() }
            .show()
    }

    private fun armarSono(millis: Long) {
        sleepDeadline = System.currentTimeMillis() + millis
        main.postDelayed(sleepRunnable, millis)
        showHud(getString(R.string.dormir_em, (millis / 60000).toInt()), getString(R.string.temporizador), null)
    }

    /**
     * Três estados, e não dois.
     *
     * Antes o botão só alternava entre deitado e em pé — e o efeito colateral
     * era permanente: pedir uma orientação fixa **desliga a rotação
     * automática** até o app ser fechado. Quem tocasse uma vez para endireitar
     * um vídeo ficava sem giro pelo resto da sessão, sem nenhuma pista de que
     * tinha sido aquele toque.
     *
     * Com três, sempre existe o caminho de volta: automático → deitado → em pé
     * → automático. E o ícone acende enquanto estiver travado, para o estado
     * ser visível em vez de adivinhado.
     */
    private enum class Rotacao { AUTOMATICA, PAISAGEM, RETRATO }

    private fun toggleOrientation() {
        rotacao = when (rotacao) {
            Rotacao.AUTOMATICA -> Rotacao.PAISAGEM
            Rotacao.PAISAGEM -> Rotacao.RETRATO
            Rotacao.RETRATO -> Rotacao.AUTOMATICA
        }
        aplicarRotacao()
        showHud(
            when (rotacao) {
                Rotacao.AUTOMATICA -> getString(R.string.rotacao_automatica)
                Rotacao.PAISAGEM -> getString(R.string.travado_deitado)
                Rotacao.RETRATO -> getString(R.string.travado_em_pe)
            },
            null, null,
        )
        hideHudAfter(1400)
        scheduleControlsHide()
    }

    private fun aplicarRotacao() {
        requestedOrientation = when (rotacao) {
            // `sensor`, e não `unspecified`: o vídeo gira mesmo com o bloqueio
            // de rotação do sistema ligado, que é o que se espera de um player
            // — ninguém desliga o bloqueio só para deitar o celular no sofá.
            Rotacao.AUTOMATICA -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            Rotacao.PAISAGEM -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Rotacao.RETRATO -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        atualizarIlha()
    }

    private var capturando = false

    private fun takeSnapshot() {
        if (capturando) return
        capturando = true
        // Na TV o quadro vem do arquivo e leva uns segundos (ver VlcEngine.snapshot).
        if (isTv) showHud(getString(R.string.capturando), null, null)
        escopo.launch {
            try {
                val imagem = engine.snapshot()
                if (imagem == null) {
                    showHud(getString(R.string.nada_capturar), null, null)
                    hideHudAfter(1500)
                    return@launch
                }
                val salvo = salvarNaGaleria(imagem)
                showHud(getString(if (salvo) R.string.salvo_imagens else R.string.nao_deu_salvar), null, null)
                hideHudAfter(1800)
            } finally {
                capturando = false
            }
        }
    }

    private fun salvarNaGaleria(imagem: Bitmap): Boolean = runCatching {
        val nome = "libertyx_${System.currentTimeMillis()}.jpg"
        val valores = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nome)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LibertyX")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores)
            ?: return false
        contentResolver.openOutputStream(uri)?.use { saida ->
            imagem.compress(Bitmap.CompressFormat.JPEG, 92, saida)
        }
        true
    }.getOrDefault(false)

    // MARK: - Janela flutuante

    /**
     * Aqui ela existe de verdade.
     *
     * No iOS a janelinha só é montada pelo sistema sobre a camada do AVPlayer,
     * e como quem toca é o VLC, o botão vira uma explicação. No Android a
     * janela é um modo da atividade inteira, independente de quem desenha —
     * então o mesmo botão, com o mesmo motor, simplesmente funciona.
     */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, getString(R.string.sem_pip), Toast.LENGTH_SHORT).show()
            return
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
            .onFailure { Toast.makeText(this, getString(R.string.pip_desativado), Toast.LENGTH_LONG).show() }
    }

    private fun isInPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    override fun onPictureInPictureModeChanged(inPip: Boolean, config: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, config)
        // Na janelinha não cabe barra nenhuma: ela tem a altura de um dedo.
        setControlsVisible(!inPip)
        ui.hud.visibility = View.GONE
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Sair pelo botão de início com o vídeo tocando entra na janelinha, em
        // vez de parar — é o que todo player de vídeo do Android faz.
        // Em televisão não há janelinha para onde ir, e tentar entrar nela
        // deixaria o vídeo tocando sem tela ao sair do app.
        // Sem o Pro, sair pausa em vez de abrir a janela — e sem tela de
        // compra no caminho de quem só quis ir à tela inicial.
        if (!isTv && Pro.ativo && engine.state == PlaybackState.Playing &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            enterPip()
        }
    }
}

/** Até onde o atraso da legenda vai, para cada lado, em segundos. */
private const val ATRASO_MAXIMO = 30.0
