package com.mauricio.libertyx.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.TextureView
import android.view.ViewGroup
import com.mauricio.libertyx.core.Device
import com.mauricio.libertyx.core.EscolhaDeFaixas
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.rede.ContasDeRede
import com.mauricio.libertyx.smb.SmbBrowser
import com.mauricio.libertyx.smb.SmbServerStore
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs
import com.mauricio.libertyx.core.Textos
import com.mauricio.libertyx.R
import com.mauricio.libertyx.library.QuadroDoVlc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Forced", "Forçada(s)", "Forzado(s)", "Forcé(e)(s)" — palavra inteira, em qualquer caixa. */
private val FORCADA = Regex("""(?i)(^|[^\p{L}])(forced|for[çcz]ad[oa]s?|forcée?s?)([^\p{L}]|$)""")

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Loading : PlaybackState
    data object Ready : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
    data object Ended : PlaybackState
    data class Failed(val message: String) : PlaybackState
}

/** Uma faixa selecionável dentro do arquivo. */
data class MediaTrack(val id: Int, val label: String)

/**
 * O motor de reprodução, sobre o libVLC.
 *
 * Mesma escolha do app de iOS e pelos mesmos motivos: são vinte anos de
 * correções acumuladas em sincronia de som e imagem, buffer de rede e
 * recuperação de erro. E ele fala SMB nativamente, o que transforma toda a
 * ponte de leitura por blocos numa URL `smb://`.
 *
 * A interface inteira — gestos, controles — conversa só com esta classe, nunca
 * com o `MediaPlayer` do VLC. É o mesmo contrato do `PlaybackEngine` do iOS, e
 * existe pela mesma razão: os gestos são a cara do app e não podem ser
 * reescritos quando o motor mudar.
 */
class VlcEngine(private val context: Context) {

    private val libVlc = Vlc.get(context)
    private val player = MediaPlayer(libVlc)
    private val naTv = Device.isTv(context)
    private val main = Handler(Looper.getMainLooper())

    /**
     * O descritor do arquivo, aberto enquanto a reprodução durar.
     *
     * `content://` de outro app não tem caminho que o VLC possa abrir sozinho;
     * quem tem permissão de ler é o nosso processo. Fechar isto cedo é o
     * equivalente exato do escopo de segurança fechado no iOS: o contêiner é
     * reconhecido, nenhuma faixa aparece, e parece arquivo inválido.
     */
    private var fileDescriptor: ParcelFileDescriptor? = null

    private var videoLayout: VLCVideoLayout? = null

    /**
     * Só o som, sem decodificar imagem.
     *
     * É o modo do carro e do rádio: com a tela apagada, decodificar 1080p para
     * jogar fora cada quadro gasta bateria e processador para nada. Precisa ser
     * decidido antes da carga, porque vira uma opção do próprio arquivo.
     */
    var audioOnly = false

    var state: PlaybackState = PlaybackState.Idle
        private set(value) {
            if (field == value) return
            field = value
            onStateChange?.invoke(value)
        }

    var onTimeUpdate: ((Double) -> Unit)? = null
    var onStateChange: ((PlaybackState) -> Unit)? = null
    var onBufferingChange: ((Boolean) -> Unit)? = null
    /** Quando o VLC descobre as faixas — só aí a lista de áudio/legenda existe. */
    var onTracksChange: (() -> Unit)? = null

    /**
     * Avisa a interface só na mudança.
     *
     * Emitir a cada atualização de tempo fazia o transporte piscar; emitir só
     * quando o VLC anuncia "tocando" deixava o indicador aceso para sempre.
     * Reagir à transição resolve os dois.
     */
    private var buffering = false
        set(value) {
            if (field == value) return
            field = value
            onBufferingChange?.invoke(value)
        }

    /**
     * Até que instante do vídeo os dados já estão em mãos.
     *
     * Não existe API que responda isso — o que há são as estatísticas de
     * leitura. A conta é simples e a aproximação é honesta: a fração do
     * arquivo lida desde a última busca vale, em segundos, a mesma fração da
     * duração. Erra em vídeo de taxa muito variável, e para uma barra de
     * progresso isso não tem importância. Sem o tamanho do arquivo — caso do
     * servidor — não há o que estimar, e a barra simplesmente não mostra.
     */
    val bufferedTime: Double
        get() {
            val tamanho = tamanhoDoArquivo ?: return 0.0
            if (tamanho <= 0 || duration <= 0) return 0.0
            val lidos = player.media?.let { m -> m.stats?.demuxReadBytes?.toLong() ?: 0L } ?: 0L
            if (lidos <= 0) return 0.0
            val (bytesRef, instanteRef) = bytesNaBusca ?: (0L to 0.0)
            val desde = (lidos - bytesRef).coerceAtLeast(0L)
            return (instanteRef + desde.toDouble() / tamanho * duration).coerceAtMost(duration)
        }

    private var tamanhoDoArquivo: Long? = null

    /** De onde vem o que está tocando: no servidor tudo demora mais. */
    private var origemAtual: MediaOrigin? = null
    private val naRede: Boolean
        get() = origemAtual != null && origemAtual !is MediaOrigin.Local

    /**
     * Destino de uma busca que o VLC ainda não alcançou.
     *
     * Enquanto uma busca não termina, o VLC continua respondendo o tempo
     * **antigo** — em rede, por um ou dois segundos. É a causa do "às vezes não
     * volta": solta-se o dedo lá atrás, o VLC ainda diz o ponto de antes, a
     * barra pula de volta para ele, e um segundo arrasto nesse intervalo parte
     * do lugar errado. Até o VLC chegar, quem responde é o destino.
     */
    private data class Alvo(val tempo: Double, val desde: Long, val tentativas: Int)
    private var alvoPendente: Alvo? = null

    /** O último ponto pedido pelo dedo — o fim do arrasto tem a palavra final. */
    private var alvoDaRolagem: Double? = null

    /** Com o dedo arrastando, o tempo que o VLC informa não vai para a tela. */
    private var emRolagem = false

    /**
     * Quantos bytes já tinham sido lidos quando a posição atual começou.
     *
     * Depois de buscar, a contagem recomeça: o que estava em memória era de
     * outro trecho do arquivo.
     */
    private var bytesNaBusca: Pair<Long, Double>? = null

    private fun marcarBusca(instante: Double) {
        val lidos = player.media?.let { m -> m.stats?.demuxReadBytes?.toLong() ?: 0L } ?: 0L
        bytesNaBusca = lidos to instante
    }

    val currentTime: Double get() = alvoPendente?.tempo ?: tempoDoVlc

    private val tempoDoVlc: Double get() = player.time / 1000.0

    /** Toda ida a um ponto passa por aqui, para o destino ficar registrado. */
    private fun irPara(alvo: Double, precise: Boolean) {
        player.setTime((alvo * 1000).toLong(), !precise)
        alvoPendente = Alvo(alvo, SystemClock.uptimeMillis(), 0)
    }
    val duration: Double get() = player.length.takeIf { it > 0 }?.let { it / 1000.0 } ?: 0.0
    val isSeekable: Boolean get() = player.isSeekable

    var rate: Float
        get() = player.rate
        set(value) { player.rate = value }

    /**
     * O atraso da legenda, em segundos: positivo faz a fala escrita aparecer
     * mais tarde, negativo mais cedo. Vale para o vídeo da vez — o VLC zera a
     * cada arquivo aberto, e é o certo: o descompasso é de um arquivo, não do
     * aparelho.
     */
    var subtitleDelay: Double
        get() = player.spuDelay / 1_000_000.0
        set(value) { player.setSpuDelay((value * 1_000_000).toLong()) }

    /** O VLC trabalha com 0–200; acima de 100 é ganho além do original. */
    var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            player.volume = (field * 100).toInt()
        }

    var isMuted: Boolean = false
        set(value) {
            field = value
            player.volume = if (value) 0 else (volume * 100).toInt()
        }

    init {
        player.setEventListener(::handleEvent)
    }

    // MARK: - Superfície

    /**
     * Liga o motor à tela.
     *
     * No celular e no tablet, `useTextureView = true` de propósito, e não é
     * detalhe: com SurfaceView a imagem é desenhada por fora da janela, e
     * escalar a view não escala o vídeo — a pinça de ampliar não teria efeito
     * nenhum. Também é o que permite a captura de tela, que numa SurfaceView
     * sai preta.
     *
     * Na TV é o contrário. A TextureView passa pela composição da janela, que
     * é SDR: um filme HDR saía lavado e a TV nem entrava no modo HDR. Na
     * SurfaceView o decodificador entrega o quadro direto à tela, com o HDR
     * junto, e um 4K pesa menos ao aparelho. Pinça não há em TV, e a captura
     * de quadro tem outro caminho — ver [snapshot].
     */
    fun attach(layout: VLCVideoLayout) {
        videoLayout = layout
        player.attachViews(layout, null, true, !naTv)
    }

    fun detach() {
        player.detachViews()
        videoLayout = null
    }

    /** Reenquadra depois de girar a tela ou mudar o modo. */
    fun updateSurfaces() = player.updateVideoSurfaces()

    fun setScale(type: MediaPlayer.ScaleType) {
        player.videoScale = type
    }

    // MARK: - Carga

    /**
     * @param inicio onde começar, em segundos. Vai como opção da mídia, e não
     *   como busca depois de tocar: logo no começo o VLC ignora a busca, e só
     *   a opção garante o ponto — é o que a passagem para o áudio precisa.
     */
    fun load(item: MediaItem, inicio: Double? = null): Result<Unit> = runCatching {
        state = PlaybackState.Loading
        // Trocar de vídeo com o anterior tocando: sem parar antes, o VLC troca
        // a mídia por baixo do laço de leitura e a imagem do arquivo velho
        // continua alguns segundos por cima do novo.
        runCatching { if (player.isPlaying) player.stop() }
        closeDescriptor()

        val media = when (val origem = item.origin) {
            is MediaOrigin.Local -> mediaForLocal(origem.uri)
            is MediaOrigin.Remote -> Media(libVlc, origem.uri)
            is MediaOrigin.Smb -> {
                val loja = SmbServerStore.get(context)
                val servidor = loja.byId(origem.serverId) ?: error(Textos.get(R.string.erro_servidor_sumiu))
                Media(libVlc, SmbBrowser.uri(servidor, origem.share, origem.path)).also { m ->
                    // As credenciais vão por opção: o VLC descarta senha
                    // embutida na URL, e a leitura chegaria ao servidor como
                    // anônima.
                    SmbBrowser.credenciais(servidor, loja.password(servidor))
                        .forEach { m.addOption(it) }
                }
            }
            is MediaOrigin.WebDav, is MediaOrigin.Midia ->
                Media(libVlc, ContasDeRede.uriParaTocar(context, origem)
                    ?: error(Textos.get(R.string.erro_servidor_sumiu)))
        }

        // Outro vídeo: a escolha de legenda do anterior não vale para ele. O
        // mesmo vídeo reaberto (volta do segundo plano) mantém a escolha.
        if (item.origin != origemAtual) legendaDoUsuario = false
        pedidoDeFaixas = EscolhaDeFaixas.consumir(item.origin.resumeKey)
        idsInternos = null
        legendasDeFora.clear()
        origemAtual = item.origin
        media.setHWDecoderEnabled(true, false)
        if (audioOnly) media.addOption(":no-video")
        if (inicio != null && inicio > 0) {
            media.addOption(":start-time=" + String.format(java.util.Locale.ROOT, "%.3f", inicio))
        }
        configureBuffer(media, item.origin)
        tamanhoDoArquivo = item.fileSize
        bytesNaBusca = null
        player.media = media
        media.release()
        state = PlaybackState.Ready
    }.onFailure {
        state = PlaybackState.Failed(it.message ?: Textos.get(R.string.erro_abrir_arquivo))
    }

    private fun mediaForLocal(uri: Uri): Media {
        // `file://` o VLC abre sozinho. Qualquer outra coisa vem de outro app
        // e só o nosso processo tem permissão de ler — daí o descritor.
        if (uri.scheme == "file") return Media(libVlc, uri)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error(Textos.get(R.string.erro_android_negou))
        fileDescriptor = pfd
        return Media(libVlc, pfd.fileDescriptor)
    }

    /**
     * Quanto o VLC guarda adiantado antes de precisar.
     *
     * Dois mecanismos diferentes, e no servidor são precisos os dois:
     *
     * - `network-caching` é o fôlego do reprodutor: quantos milissegundos de
     *   vídeo já decodificado ele mantém à frente. Absorve uma engasgada da
     *   rede sem a imagem parar.
     * - `prefetch` é o fôlego da leitura: um bloco grande lido adiantado, para
     *   que o pedido seguinte não vire uma ida ao servidor.
     *
     * O que faz diferença ao arrastar a barra é o segundo: depois de uma busca,
     * o bloco novo chega de uma vez em vez de gota a gota. Em troca o vídeo
     * demora um pouco mais para começar — preço que se paga nos primeiros
     * segundos e rende o filme inteiro sem travar.
     */
    private fun configureBuffer(media: Media, origin: MediaOrigin) {
        when (origin) {
            is MediaOrigin.Local -> media.addOption(":file-caching=500")
            else -> {
                // Um segundo e meio, e não cinco.
                //
                // Este número é quanto o VLC espera acumular antes de mostrar
                // imagem — no começo do vídeo e **depois de cada busca**. Com
                // cinco segundos, cada soltada do dedo na barra virava cinco
                // segundos de tela parada, e a troca de faixa de áudio, que
                // também busca, pagava o mesmo. Quem segura a reprodução sem
                // engasgar é o prefetch logo abaixo, que lê adiantado.
                media.addOption(":network-caching=1500")
                // 32 MB adiantados: uns 20 segundos de um 1080p comum.
                media.addOption(":prefetch-buffer-size=32768")
                // Blocos de 256 KB em vez dos 16 KB padrão.
                media.addOption(":prefetch-read-size=262144")
                // Busca curta aproveita o que já está em memória em vez de
                // jogar fora e ler tudo de novo.
                media.addOption(":prefetch-seek-threshold=1048576")
            }
        }
    }

    // MARK: - Transporte

    fun play() {
        player.play()
        if (state != PlaybackState.Playing) state = PlaybackState.Playing
    }

    fun pause() {
        if (player.isPlaying) player.pause()
        state = PlaybackState.Paused
    }

    /**
     * Busca.
     *
     * `precise` é o motivo de o projeto existir. O segundo parâmetro do
     * `setTime` do VLC é `fast`: ligado, ele pula para o keyframe anterior e
     * mostra — é a rolagem aos pulos que incomoda. Desligado, ele decodifica
     * até o quadro pedido. O Android expõe essa escolha; o VLCKit do iOS não,
     * e foi essa a lacuna que quase virou um motor FFmpeg próprio lá.
     */
    fun seek(time: Double, precise: Boolean = true) {
        if (duration <= 0) return
        val alvo = time.coerceIn(0.0, duration)
        irPara(alvo, precise)
        marcarBusca(alvo)
        onTimeUpdate?.invoke(alvo)
    }

    // MARK: - Rolagem

    private var pendingScrub: Double? = null
    private var scrubScheduled = false
    private var lastScrubApplied = 0L

    /**
     * Rolagem com ritmo controlado.
     *
     * O dedo gera dezenas de eventos por segundo; mandar cada um para o VLC faz
     * ele abortar e reiniciar a busca sem parar, e a imagem trava em vez de
     * acompanhar. Aplicando no máximo a cada 80 ms, cada busca tem tempo de
     * mostrar o quadro antes da próxima — o movimento fica contínuo.
     */
    fun scrub(time: Double, precise: Boolean) {
        if (duration <= 0) return
        val alvo = time.coerceIn(0.0, duration)
        pendingScrub = alvo
        alvoDaRolagem = alvo
        onTimeUpdate?.invoke(alvo)

        val agora = System.currentTimeMillis()
        if (agora - lastScrubApplied >= cadenciaDeRolagem) {
            aplicarScrub(alvo, precise, agora)
        } else {
            agendarScrubPendente(precise)
        }
    }

    /** O dedo encostou na barra ou na tela para arrastar. */
    fun beginScrub() {
        emRolagem = true
        alvoDaRolagem = null
    }

    /**
     * Ao soltar o dedo, vai exatamente ao último ponto pedido.
     *
     * Sempre, e não só quando havia algo agendado: a busca que já tinha saído
     * pode ter sido abortada pela seguinte, e sem repetir aqui o vídeo ficaria
     * num ponto intermediário do caminho do dedo.
     */
    fun endScrub(precise: Boolean) {
        scrubScheduled = false
        pendingScrub = null
        emRolagem = false
        val alvo = alvoDaRolagem ?: return
        alvoDaRolagem = null
        lastScrubApplied = System.currentTimeMillis()
        irPara(alvo, precise)
        marcarBusca(alvo)
        onTimeUpdate?.invoke(alvo)
    }

    /**
     * Intervalo mínimo entre buscas durante o arrasto.
     *
     * No arquivo local, 80 ms: o disco responde a tempo de mostrar o quadro
     * antes da busca seguinte. No servidor não — cada busca descarta o buffer e
     * pede um bloco novo pela rede, e com buscas a cada 80 ms nenhuma chega a
     * terminar: o VLC aborta uma para começar a outra e a imagem congela até o
     * dedo parar. A 300 ms cada uma tem chance de mostrar algo.
     */
    private val cadenciaDeRolagem: Long get() = if (naRede) 300L else 80L

    private fun aplicarScrub(alvo: Double, precise: Boolean, agora: Long) {
        lastScrubApplied = agora
        pendingScrub = null
        irPara(alvo, precise)
        marcarBusca(alvo)
    }

    /**
     * Garante que o último ponto arrastado seja aplicado mesmo que o dedo pare
     * — senão a imagem fica num ponto anterior ao que a barra mostra.
     */
    private fun agendarScrubPendente(precise: Boolean) {
        if (scrubScheduled) return
        scrubScheduled = true
        main.postDelayed({
            scrubScheduled = false
            pendingScrub?.let { aplicarScrub(it, precise, System.currentTimeMillis()) }
        }, cadenciaDeRolagem)
    }

    // MARK: - Faixas

    val audioTracks: List<MediaTrack>
        get() = tracks(player.audioTracks)

    val subtitleTracks: List<MediaTrack>
        get() = tracks(player.spuTracks)

    /** `null` quando nenhuma está ligada — o VLC usa -1 para isso. */
    val currentAudioTrack: Int? get() = player.audioTrack.takeIf { it >= 0 }
    val currentSubtitleTrack: Int? get() = player.spuTrack.takeIf { it >= 0 }

    /**
     * Troca a faixa de áudio — sem esperar o cache antigo acabar.
     *
     * Ao trocar, o VLC passa a decodificar a faixa nova de onde o **leitor**
     * está, e num arquivo de rede ele fica cinco segundos à frente do que se
     * vê. Até a imagem alcançar esse ponto o filme corre mudo, e parece que o
     * app ignorou o toque. Uma busca exata para o instante atual joga fora o
     * que estava adiantado e refaz o mesmo trecho já com a faixa escolhida: o
     * som volta no tempo de encher o buffer, e a imagem não sai do lugar.
     */
    fun selectAudioTrack(id: Int) {
        if (id == player.audioTrack) return
        player.setAudioTrack(id)
        ressincronizar()
    }

    /** `null` desliga a legenda. */
    fun selectSubtitleTrack(id: Int?) {
        legendaDoUsuario = true
        val alvo = id ?: -1
        if (alvo == player.spuTrack) return
        player.setSpuTrack(alvo)
        // Desligar não faz esperar por nada; ligar tem a mesma espera do áudio.
        if (id != null) ressincronizar()
    }

    /** Refaz o trecho atual para a faixa recém-escolhida valer já. */
    private fun ressincronizar() {
        val agora = player.time
        if (duration <= 0 || agora <= 0 || !player.isSeekable) return
        // `false` é o `fast` do VLC: busca exata, para voltar ao mesmo quadro
        // em vez de recuar até o keyframe anterior.
        player.setTime(agora, false)
        marcarBusca(agora / 1000.0)
    }

    /** Legenda de um arquivo ao lado, escolhido pelo usuário. */
    fun addSubtitle(uri: Uri, selecionar: Boolean = true): Boolean {
        if (selecionar) legendaDoUsuario = true
        // As de dentro do arquivo são as que existiam antes da primeira de
        // fora; as de fora chegam depois, na ordem em que entram aqui.
        if (idsInternos == null) idsInternos = player.spuTracks.orEmpty().map { it.id }.filter { it >= 0 }.toSet()
        uri.lastPathSegment?.let { legendasDeFora += it }
        return player.addSlave(IMedia.Slave.Type.Subtitle, uri, selecionar)
    }

    /**
     * As faixas de antes de o motor ser refeito (ver [TamanhoDaLegenda]):
     * o mesmo áudio e a mesma legenda voltam assim que existirem de novo.
     * A legenda de fora chega depois das outras; tenta-se a cada faixa nova.
     */
    private var faixasARestaurar: Pair<Int?, Int?>? = null

    fun restaurarFaixas(audio: Int?, legenda: Int?) {
        faixasARestaurar = audio to legenda
        legendaDoUsuario = true
    }

    private fun aplicarRestauracao() {
        val (audio, legenda) = faixasARestaurar ?: return
        if (audio != null && player.audioTracks.orEmpty().any { it.id == audio } && player.audioTrack != audio) {
            player.setAudioTrack(audio)
        }
        val alvo = legenda ?: -1
        if (alvo == -1 || player.spuTracks.orEmpty().any { it.id == alvo }) {
            if (player.spuTrack != alvo) player.setSpuTrack(alvo)
            faixasARestaurar = null
        }
    }

    /** O áudio e a legenda escolhidos na página do servidor, à espera de as faixas aparecerem. */
    private var pedidoDeFaixas: EscolhaDeFaixas.Pedido? = null
    private var idsInternos: Set<Int>? = null
    private val legendasDeFora = mutableListOf<String>()

    /**
     * Aplica o que foi escolhido na página do servidor assim que a faixa
     * pedida existe. As de dentro do arquivo aparecem ao abrir; a legenda de
     * fora, só depois de baixada — por isso isto roda a cada faixa nova.
     */
    private fun aplicarPedidoDeFaixas() {
        var pedido = pedidoDeFaixas ?: return
        pedido.audio?.let { posicao ->
            val faixas = audioTracks
            if (posicao < faixas.size) {
                if (player.audioTrack != faixas[posicao].id) {
                    player.setAudioTrack(faixas[posicao].id)
                    if (state == PlaybackState.Playing) ressincronizar()
                }
                pedido = pedido.copy(audio = null)
            }
        }
        pedido.legenda?.let { legenda ->
            val todas = player.spuTracks.orEmpty().filter { it.id >= 0 }
            val internos = idsInternos
            val internas = todas.filter { internos == null || it.id in internos }
            val deFora = todas.filter { internos != null && it.id !in internos }
            val alvo: Int? = when (legenda) {
                EscolhaDeFaixas.Legenda.Desligada -> -1
                is EscolhaDeFaixas.Legenda.Interna -> internas.getOrNull(legenda.posicao)?.id
                is EscolhaDeFaixas.Legenda.Externa -> {
                    val ordem = legendasDeFora.indexOfFirst { it.contains("(${legenda.indice}).") }
                    if (ordem >= 0) deFora.getOrNull(ordem)?.id else null
                }
            }
            if (alvo != null) {
                legendaDoUsuario = true
                if (player.spuTrack != alvo) player.setSpuTrack(alvo)
                pedido = pedido.copy(legenda = null)
            }
        }
        pedidoDeFaixas = pedido.takeIf { it.audio != null || it.legenda != null }
    }

    /** Quem escolheu a legenda no menu manda nela até o vídeo trocar. */
    private var legendaDoUsuario = false

    /**
     * Ao abrir, só a legenda **forçada** fica ligada — a que traduz a placa, a
     * carta ou o idioma estrangeiro no meio do filme. A completa vem desligada:
     * o VLC liga a que o arquivo marca como padrão, e quase sempre ela está no
     * mesmo idioma do áudio, repetindo o que já se ouve.
     *
     * O libVLC 3 não diz se a faixa é forçada; o nome diz — é como os arquivos
     * a marcam ("Forced", "Forçada", "Forzados"), e é o que aparece no menu.
     * Chamada a cada faixa que surge, porque a legenda ao lado do arquivo chega
     * depois das de dentro dele.
     */
    private fun aplicarRegraDaLegenda() {
        if (legendaDoUsuario || pedidoDeFaixas?.legenda != null) return
        val faixas = player.spuTracks.orEmpty().filter { it.id >= 0 }
        val alvo = faixas.firstOrNull { ehForcada(it.name) }?.id ?: -1
        if (player.spuTrack != alvo) player.setSpuTrack(alvo)
    }

    private fun ehForcada(nome: String?): Boolean = nome != null && FORCADA.containsMatchIn(nome)

    private fun tracks(descricoes: Array<MediaPlayer.TrackDescription>?): List<MediaTrack> =
        descricoes.orEmpty()
            // O índice -1 é a entrada "Desativado" que o VLC inclui; a
            // interface já oferece essa opção por conta própria.
            .filter { it.id >= 0 }
            .map { MediaTrack(it.id, it.name ?: Textos.get(R.string.faixa_n, it.id)) }

    // MARK: - Captura

    /**
     * O quadro que está na tela agora.
     *
     * Funciona porque a superfície é uma TextureView — ver [attach]. Numa
     * SurfaceView isto devolveria um retângulo preto, que é o modo mais
     * convincente de uma captura de tela parecer quebrada; por isso, na TV, o
     * quadro vem do arquivo ([quadroDoArquivo]).
     */
    suspend fun snapshot(): Bitmap? {
        if (naTv) return quadroDoArquivo()
        val layout = videoLayout ?: return null
        val textura = encontrarTextura(layout) ?: return null
        return textura.bitmap
    }

    /**
     * O mesmo instante, tirado do arquivo por uma segunda leitura invisível —
     * a das miniaturas, no tamanho do vídeo (até 4K).
     *
     * Leva uns segundos, e um quadro HDR sai convertido para SDR: é uma foto
     * para a galeria, não precisa do brilho da TV.
     */
    private suspend fun quadroDoArquivo(): Bitmap? {
        val origem = origemAtual ?: return null
        val instante = player.time.takeIf { it >= 0 } ?: return null
        val faixa = runCatching { player.currentVideoTrack }.getOrNull()
        val sar = if (faixa != null && faixa.sarNum > 0 && faixa.sarDen > 0) faixa.sarNum.toDouble() / faixa.sarDen else 1.0
        var largura = ((faixa?.width ?: 0) * sar).toInt().takeIf { it > 0 } ?: 1920
        var altura = faixa?.height?.takeIf { it > 0 } ?: 1080
        val reducao = minOf(1.0, 3840.0 / largura, 2160.0 / altura)
        largura = (largura * reducao).toInt() and 1.inv()
        altura = (altura * reducao).toInt() and 1.inv()

        // Um descritor próprio: o do player está sendo lido, e dois leitores
        // no mesmo descritor brigariam pela posição no arquivo.
        var descritor: ParcelFileDescriptor? = null
        val (uri, opcoes) = withContext(Dispatchers.IO) {
            when (origem) {
                is MediaOrigin.Local ->
                    if (origem.uri.scheme == "file") origem.uri to emptyList()
                    else context.contentResolver.openFileDescriptor(origem.uri, "r")
                        ?.also { descritor = it }
                        ?.let { Uri.parse("fd://${it.fd}") to emptyList<String>() }
                is MediaOrigin.Remote -> origem.uri to emptyList()
                is MediaOrigin.Smb -> {
                    val loja = SmbServerStore.get(context)
                    loja.byId(origem.serverId)?.let { servidor ->
                        SmbBrowser.uri(servidor, origem.share, origem.path) to
                            SmbBrowser.credenciais(servidor, loja.password(servidor))
                    }
                }
                is MediaOrigin.WebDav, is MediaOrigin.Midia ->
                    ContasDeRede.uriDeReproducao(context, origem)?.let { it to emptyList() }
            }
        } ?: return null
        return try {
            QuadroDoVlc.extrair(context, uri, opcoes, largura = largura, altura = altura, emMs = instante)?.imagem
        } finally {
            runCatching { descritor?.close() }
        }
    }

    private fun encontrarTextura(raiz: ViewGroup): TextureView? {
        for (i in 0 until raiz.childCount) {
            when (val filho = raiz.getChildAt(i)) {
                is TextureView -> return filho
                is ViewGroup -> encontrarTextura(filho)?.let { return it }
            }
        }
        return null
    }

    // MARK: - Fim

    fun teardown() {
        // Quem ouvia não ouve mais: o último aviso ("parado") sairia com o
        // VLC já liberado, e quem perguntasse o tempo nessa hora derrubaria o
        // app — era o que acontecia ao fechar o serviço de áudio.
        onStateChange = null
        onTimeUpdate = null
        runCatching { player.setEventListener(null) }
        runCatching { player.stop() }
        runCatching { player.detachViews() }
        runCatching { player.release() }
        // Solta o arquivo só depois de parar: fechar antes deixaria o VLC lendo
        // um descritor já inválido.
        closeDescriptor()
        state = PlaybackState.Idle
    }

    private fun closeDescriptor() {
        runCatching { fileDescriptor?.close() }
        fileDescriptor = null
    }

    // MARK: - Estado vindo do VLC

    private fun handleEvent(evento: MediaPlayer.Event) {
        when (evento.type) {
            MediaPlayer.Event.Opening -> state = PlaybackState.Loading
            MediaPlayer.Event.Buffering ->
                // O VLC emite "buffering" durante a reprodução normal, com
                // percentual em 100. Só o que está abaixo disso é espera de
                // verdade; sem esta comparação o indicador pisca sem parar.
                buffering = evento.buffering < 100f
            MediaPlayer.Event.Playing -> {
                buffering = false
                state = PlaybackState.Playing
                aplicarRestauracao()
                aplicarPedidoDeFaixas()
                aplicarRegraDaLegenda()
            }
            MediaPlayer.Event.Paused -> state = PlaybackState.Paused
            MediaPlayer.Event.Stopped -> state = PlaybackState.Idle
            MediaPlayer.Event.EndReached -> state = PlaybackState.Ended
            MediaPlayer.Event.EncounteredError ->
                state = PlaybackState.Failed(Textos.get(R.string.erro_vlc_abrir))
            MediaPlayer.Event.TimeChanged -> {
                // Tempo andando é a prova de que não está carregando — mais
                // confiável que o estado anunciado.
                buffering = false
                val doVlc = evento.timeChanged / 1000.0

                val pendente = alvoPendente
                if (pendente != null) {
                    if (abs(doVlc - pendente.tempo) < 3) {
                        alvoPendente = null
                    } else {
                        // Não chegou. Passado o prazo, pede de novo uma vez —
                        // busca abortada no meio de uma rajada é o caso comum —
                        // e, se ainda assim não chegar, aceita onde o VLC está,
                        // para a tela não mentir para sempre.
                        val prazo = if (naRede) 2_500L else 1_200L
                        if (SystemClock.uptimeMillis() - pendente.desde > prazo) {
                            if (pendente.tentativas == 0) {
                                player.setTime((pendente.tempo * 1000).toLong(), false)
                                alvoPendente = pendente.copy(
                                    desde = SystemClock.uptimeMillis(), tentativas = 1,
                                )
                            } else {
                                alvoPendente = null
                            }
                        }
                        if (alvoPendente != null) return
                    }
                }

                // Com o dedo arrastando, quem manda na tela é o dedo. O tempo
                // do VLC pula de quadro-chave em quadro-chave e brigava com o
                // destino: a barra e o número tremiam entre os dois.
                if (emRolagem) return
                onTimeUpdate?.invoke(doVlc)
            }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> {
                aplicarRestauracao()
                aplicarPedidoDeFaixas()
                aplicarRegraDaLegenda()
                onTracksChange?.invoke()
            }
            // O VLC liga a legenda padrão do arquivo por conta própria, às
            // vezes depois de anunciar as faixas: a regra vale de novo.
            MediaPlayer.Event.ESSelected -> aplicarRegraDaLegenda()
        }
    }
}
