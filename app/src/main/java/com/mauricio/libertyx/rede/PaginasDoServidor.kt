package com.mauricio.libertyx.rede

import android.graphics.Bitmap
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.Device
import com.mauricio.libertyx.core.Escala
import com.mauricio.libertyx.core.EscolhaDeFaixas
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.ResumeStore
import com.mauricio.libertyx.core.TimeFormat
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.library.MenuDoItem
import com.mauricio.libertyx.library.OpcaoDoMenu
import com.mauricio.libertyx.library.Secao
import com.mauricio.libertyx.library.rememberVoltaAoApp
import com.mauricio.libertyx.player.Playback
import com.mauricio.libertyx.rede.ServidorDeMidia.Item
import com.mauricio.libertyx.tv.RolagemDeTvNasBordas
import com.mauricio.libertyx.tv.tvFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// MARK: - Peças comuns

/**
 * O fundo apagando para o preto da página, com o logotipo por cima — é a cara
 * que o app do servidor dá às páginas de filme, episódio e série.
 */
@Composable
private fun Cabeca(servidor: ServidorDeMidia, fundoDe: String, logoDe: String?) {
    // 16:9 no celular em pé; numa tela deitada (TV, tablet) isso tomaria a
    // tela inteira e empurraria os botões para fora — lá vai até a metade.
    val tela = androidx.compose.ui.platform.LocalConfiguration.current
    val altura = minOf(tela.screenWidthDp * 9f / 16f, tela.screenHeightDp * 0.5f).dp
    Box(Modifier.fillMaxWidth().height(altura)) {
        ImagemDoServidor(servidor, fundoDe, listOf("Backdrop", "Thumb", "Primary"), 1280, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.45f to Color.Transparent, 0.8f to LabTheme.background.copy(alpha = 0.6f), 1f to LabTheme.background,
                )
            )
        )
        if (logoDe != null) {
            LogoDoServidor(
                servidor, logoDe,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                    .widthIn(max = 260.dp * Escala.fator()).heightIn(max = 84.dp * Escala.fator()),
            )
        }
    }
}

/** O logotipo é PNG transparente: vai inteiro, sem corte e sem fundo. */
@Composable
private fun LogoDoServidor(servidor: ServidorDeMidia, id: String, modifier: Modifier) {
    val imagem by produceState<Bitmap?>(null, id) {
        value = (servidor.baixarImagem(id, listOf("Logo"), 800) as? ServidorDeMidia.ImagemBaixada.Pronta)?.bitmap
    }
    imagem?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit) }
}

/**
 * Um botão da linha de ícones, como no app do Jellyfin: só o símbolo, aceso
 * na cor dele quando marcado. O de tocar vem num círculo dourado — é a ação
 * da página, e tem de se achar sem procurar.
 */
@Composable
private fun BotaoDeIcone(
    icone: ImageVector,
    descricao: String,
    modifier: Modifier = Modifier,
    cor: Color = LabTheme.text,
    aceso: Boolean = false,
    destaque: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(54.dp).tvFocus(27.dp).clip(CircleShape)
            .background(if (destaque) cor else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icone, descricao,
            tint = when {
                destaque -> Color.Black
                aceso -> cor
                else -> LabTheme.text
            },
            modifier = Modifier.size(if (destaque) 30.dp else 26.dp),
        )
    }
}

/** Sinopse em quatro linhas; um toque abre o resto. */
@Composable
private fun Sinopse(texto: String) {
    var aberta by remember { mutableStateOf(false) }
    Text(
        texto, color = LabTheme.text.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 20.sp,
        maxLines = if (aberta) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().tvFocus(8.dp, scale = 1.01f).clip(RoundedCornerShape(8.dp))
            .clickable { aberta = !aberta },
    )
}

@Composable
private fun Credito(rotulo: String, nomes: List<String>) {
    if (nomes.isEmpty()) return
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(rotulo, color = LabTheme.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(84.dp))
        Text(nomes.take(6).joinToString(", "), color = LabTheme.text, fontSize = 12.5.sp)
    }
}

/** O elenco com foto; tocar numa pessoa abre a página dela. */
@Composable
private fun FaixaDoElenco(servidor: ServidorDeMidia, elenco: List<ServidorDeMidia.Pessoa>, onPessoa: (ServidorDeMidia.Pessoa) -> Unit) {
    Column(Modifier.padding(top = 22.dp)) {
        Box(Modifier.padding(horizontal = 16.dp)) { Secao(stringResource(R.string.pg_elenco)) }
        RolagemDeTvNasBordas(margem = 28.dp) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(elenco, key = { it.id + it.tipo + (it.papel ?: "") }) { pessoa ->
                    Column(
                        Modifier.width(96.dp * Escala.fator()).tvFocus(10.dp).clip(RoundedCornerShape(10.dp))
                            .clickable { onPessoa(pessoa) },
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) {
                            ImagemDoServidor(servidor, pessoa.id, listOf("Primary"), 240, Modifier.fillMaxSize(), Icons.Filled.Person)
                        }
                        Text(
                            pessoa.nome, color = LabTheme.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                        )
                        pessoa.papel?.let {
                            Text(it, color = LabTheme.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Uma linha "Áudio  [Brazilian Portuguese - AC3 ⇕]" que abre a lista de opções. */
@Composable
private fun Seletor(rotulo: String, valor: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(rotulo, color = LabTheme.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(84.dp))
        Row(
            Modifier.weight(1f).tvFocus(8.dp, scale = 1.02f).clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.06f)).clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(valor, color = LabTheme.text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.UnfoldMore, null, tint = LabTheme.muted, modifier = Modifier.size(16.dp))
        }
    }
}

private fun dataCurta(contexto: android.content.Context, ms: Long): String =
    DateFormat.getDateFormat(contexto).format(java.util.Date(ms))

private fun hora(contexto: android.content.Context, ms: Long): String =
    DateFormat.getTimeFormat(contexto).format(java.util.Date(ms))

// MARK: - Página do vídeo

/** A legenda escolhida no seletor, pelo número do servidor. */
private sealed interface LegendaEscolhida {
    data object Automatica : LegendaEscolhida
    data object Desligada : LegendaEscolhida
    data class Faixa(val indice: Int) : LegendaEscolhida
}

private enum class Seletores { AUDIO, LEGENDA, QUALIDADE, MAIS }

/**
 * A página de um filme ou episódio do Jellyfin ou Emby antes de tocar, como no
 * app do próprio servidor: fundo e logotipo, data, duração, nota, a que horas
 * termina, os botões em ícones, as faixas do arquivo com seletores, a
 * sinopse, os outros episódios da temporada, o elenco e os extras.
 *
 * Nada disto é do app: os textos, as fotos e o logotipo são o que os
 * provedores de metadados do servidor trouxeram, e as faixas são a leitura
 * que ele fez do arquivo.
 */
@Composable
fun PaginaDoVideo(
    conta: ContaDeRede,
    servidor: ServidorDeMidia,
    inicial: Item,
    onSerie: (serieId: String, nome: String) -> Unit,
    onPessoa: (ServidorDeMidia.Pessoa) -> Unit,
    onVideo: (Item) -> Unit,
) {
    val contexto = LocalContext.current
    val naTv = remember { Device.isTv(contexto) }
    val links = LocalUriHandler.current
    val escopo = rememberCoroutineScope()
    val volta = rememberVoltaAoApp()
    var recarga by remember { mutableIntStateOf(0) }

    var item by remember(inicial.id) { mutableStateOf(inicial) }
    var detalhes by remember(inicial.id) { mutableStateOf<ServidorDeMidia.Detalhes?>(null) }
    var temporada by remember(inicial.id) { mutableStateOf<List<Item>>(emptyList()) }
    var extras by remember(inicial.id) { mutableStateOf<List<Item>>(emptyList()) }
    var erro by remember(inicial.id) { mutableStateOf<String?>(null) }

    var audio by remember(inicial.id) { mutableStateOf<Int?>(null) }
    var legenda by remember(inicial.id) { mutableStateOf<LegendaEscolhida>(LegendaEscolhida.Automatica) }
    var qualidade by remember(conta.id) { mutableIntStateOf(Qualidade.da(contexto, conta.id)) }
    var seletor by remember { mutableStateOf<Seletores?>(null) }
    val focoInicial = remember { FocusRequester() }

    LaunchedEffect(inicial.id, volta, recarga) {
        // Quem acabou de fechar o player chega antes do "parou": sem esperar,
        // o ponto onde parou viria velho.
        AvisoAoServidor.emDia()
        runCatching { servidor.detalhes(inicial.id) }
            .onSuccess { (novo, lido) ->
                item = novo
                detalhes = lido
                erro = null
                if (audio == null) {
                    val audios = lido.faixas("Audio")
                    audio = (audios.firstOrNull { it.padrao } ?: audios.firstOrNull())?.indice
                }
                if (novo.tipo == "Episode") {
                    runCatching { servidor.temporada(novo) }.onSuccess { temporada = it }
                }
                if (lido.extras > 0 && extras.isEmpty()) {
                    runCatching { servidor.extras(novo.id) }.onSuccess { extras = it }
                }
            }
            .onFailure {
                if (it is CancellationException) throw it
                if (detalhes == null) erro = it.message ?: it.javaClass.simpleName
            }
    }
    if (naTv) LaunchedEffect(detalhes != null) { runCatching { focoInicial.requestFocus() } }

    /** Leva os seletores ao player: a posição conta só as faixas de dentro do arquivo. */
    fun pedirFaixas(chave: String) {
        val d = detalhes ?: return
        val audios = d.faixas("Audio").filter { !it.externo }
        val padrao = (audios.firstOrNull { it.padrao } ?: audios.firstOrNull())?.indice
        var pedido = EscolhaDeFaixas.Pedido()
        if (qualidade > 0) {
            // Na conversão o servidor manda uma faixa só: a escolhida.
            Conversoes.pedirAudio(item.id, audio)
        } else if (audio != null && audio != padrao) {
            pedido = pedido.copy(audio = audios.indexOfFirst { it.indice == audio }.takeIf { it >= 0 })
        }
        when (val escolhida = legenda) {
            LegendaEscolhida.Automatica -> {}
            LegendaEscolhida.Desligada -> pedido = pedido.copy(legenda = EscolhaDeFaixas.Legenda.Desligada)
            is LegendaEscolhida.Faixa -> {
                val legendas = d.faixas("Subtitle")
                val faixa = legendas.firstOrNull { it.indice == escolhida.indice }
                if (faixa != null) {
                    pedido = if (faixa.externo) {
                        pedido.copy(legenda = EscolhaDeFaixas.Legenda.Externa(faixa.indice))
                    } else {
                        val posicao = legendas.filter { !it.externo }.indexOfFirst { it.indice == faixa.indice }
                        if (posicao >= 0) pedido.copy(legenda = EscolhaDeFaixas.Legenda.Interna(posicao)) else pedido
                    }
                }
            }
        }
        EscolhaDeFaixas.pedir(pedido, chave)
    }

    /** Episódio toca com a temporada em volta, para o "próximo" do player ir ao seguinte. */
    fun tocar(inicio: EscolhaDeFaixas.Inicio?) {
        val atual = item
        val lista = if (atual.tipo == "Episode" && temporada.any { it.id == atual.id }) temporada else listOf(atual)
        val alvo = paraTocar(conta, atual)
        pedirFaixas(alvo.origin.resumeKey)
        inicio?.let { EscolhaDeFaixas.pedirInicio(it, alvo.origin.resumeKey) }
        Playback.start(contexto, alvo, lista.map { paraTocar(conta, it) })
    }

    fun tocarSozinho(video: Item) {
        val unico = paraTocar(conta, video)
        Playback.start(contexto, unico, listOf(unico))
    }

    fun marcar(acao: suspend () -> Unit) {
        escopo.launch {
            runCatching { acao() }
                .onSuccess { recarga++ }
                .onFailure {
                    if (it is CancellationException) throw it
                    Toast.makeText(contexto, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show()
                }
        }
    }

    val d = detalhes
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (erro != null) Text(erro!!, color = LabTheme.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            else CircularProgressIndicator()
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item(key = "cabeca") { Cabeca(servidor, d.fundoDe ?: item.serieId ?: item.id, d.logoDe) }
        item(key = "titulos") {
            Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 760.dp)) {
                val serieId = item.serieId
                if (item.tipo == "Episode" && serieId != null) {
                    Row(
                        Modifier.tvFocus(8.dp, scale = 1.02f).clip(RoundedCornerShape(8.dp))
                            .clickable { onSerie(serieId, item.serie.orEmpty()) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.serie ?: item.nome, color = LabTheme.text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        Icon(Icons.Filled.ChevronRight, null, tint = LabTheme.muted, modifier = Modifier.size(26.dp))
                    }
                    Text(
                        listOfNotNull(d.nomeDaTemporada, item.numero?.let { "$it. ${item.nome}" } ?: item.nome).joinToString(" · "),
                        color = LabTheme.text.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text(item.nome, color = LabTheme.text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val partes = buildList {
                        if (item.tipo == "Episode" && d.estreia != null) add(dataCurta(contexto, d.estreia))
                        else item.ano?.let { add(it.toString()) }
                        item.duracao?.let { duracao ->
                            add(TimeFormat.spoken(duracao))
                            val fim = System.currentTimeMillis() + ((duracao - (item.posicao ?: 0.0)) * 1000).toLong()
                            add(stringResource(R.string.pg_termina_as, hora(contexto, fim)))
                        }
                    }
                    Text(partes.joinToString("   "), color = LabTheme.muted, fontSize = 12.5.sp, maxLines = 1)
                    d.nota?.let {
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Filled.Star, null, tint = LabTheme.accent, modifier = Modifier.size(14.dp))
                        Text(String.format(java.util.Locale.ROOT, " %.1f", it), color = LabTheme.muted, fontSize = 12.5.sp)
                    }
                    d.classificacao?.let {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            it, color = LabTheme.muted, fontSize = 11.sp,
                            modifier = Modifier.border(1.dp, LabTheme.faint, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp),
                        )
                    }
                }
            }
        }
        item(key = "botoes") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val comecado = item.posicao != null
                    BotaoDeIcone(
                        Icons.Filled.PlayArrow,
                        stringResource(if (comecado) R.string.pg_continuar else R.string.pg_assistir),
                        Modifier.focusRequester(focoInicial), cor = LabTheme.accent, destaque = true,
                    ) { tocar(if (comecado) EscolhaDeFaixas.Inicio.CONTINUAR else null) }
                    if (comecado) {
                        BotaoDeIcone(Icons.Filled.Replay, stringResource(R.string.pg_do_inicio)) {
                            tocar(EscolhaDeFaixas.Inicio.DO_COMECO)
                        }
                    }
                    BotaoDeIcone(Icons.Filled.Check, stringResource(R.string.ja_reproduzido), cor = LabTheme.accent, aceso = item.visto) {
                        val novo = !item.visto
                        marcar {
                            servidor.marcarVisto(item.id, novo)
                            // O servidor zera onde parou nos dois sentidos; a marca daqui segue.
                            ResumeStore.get(contexto).clear(MediaOrigin.Midia(conta.id, item.id).resumeKey)
                        }
                    }
                    BotaoDeIcone(
                        if (item.favorito) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        stringResource(R.string.pg_favorito), cor = LabTheme.red, aceso = item.favorito,
                    ) {
                        val novo = !item.favorito
                        marcar { servidor.marcarFavorito(item.id, novo) }
                    }
                    BotaoDeIcone(Icons.Filled.MoreHoriz, stringResource(R.string.pg_mais)) { seletor = Seletores.MAIS }
                }
                val posicao = item.posicao
                val duracao = item.duracao
                if (posicao != null && duracao != null && item.progresso != null) {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(item.progresso!!).background(LabTheme.accent))
                        }
                        Text(
                            stringResource(R.string.pg_parou_em, TimeFormat.clock(posicao), TimeFormat.spoken((duracao - posicao).coerceAtLeast(0.0))),
                            color = LabTheme.faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
            }
        }
        item(key = "faixas") {
            val audios = d.faixas("Audio")
            val legendas = d.faixas("Subtitle")
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(LabTheme.radiusSmall))
                    .background(LabTheme.glass).padding(14.dp),
            ) {
                d.faixas("Video").firstOrNull()?.let {
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(stringResource(R.string.pg_video), color = LabTheme.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(84.dp))
                        Text(it.titulo, color = LabTheme.text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (audios.isNotEmpty()) {
                    Seletor(stringResource(R.string.pg_audio), (audios.firstOrNull { it.indice == audio } ?: audios.first()).titulo) {
                        seletor = Seletores.AUDIO
                    }
                }
                Seletor(
                    stringResource(R.string.pg_legendas),
                    when (val l = legenda) {
                        LegendaEscolhida.Automatica ->
                            if (legendas.isEmpty()) stringResource(R.string.pg_nenhuma) else stringResource(R.string.pg_legenda_auto)
                        LegendaEscolhida.Desligada -> stringResource(R.string.pg_legenda_desligada)
                        is LegendaEscolhida.Faixa -> legendas.firstOrNull { it.indice == l.indice }?.titulo.orEmpty()
                    },
                ) { seletor = Seletores.LEGENDA }
                Seletor(stringResource(R.string.pg_qualidade), Qualidade.nome(qualidade)) { seletor = Seletores.QUALIDADE }
                if (qualidade > 0) {
                    Text(stringResource(R.string.pg_conversao_aviso), color = LabTheme.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        d.sinopse?.let { sinopse ->
            item(key = "sinopse") { Box(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) { Sinopse(sinopse) } }
        }
        item(key = "creditos") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Credito(stringResource(R.string.pg_generos), d.generos)
                Credito(stringResource(R.string.pg_direcao), d.pessoasCom("Director").map { it.nome })
                Credito(stringResource(R.string.pg_roteiro), d.pessoasCom("Writer").map { it.nome })
            }
        }
        if (temporada.size > 1) item(key = "temporada") {
            Column(Modifier.padding(top = 22.dp)) {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    Secao(stringResource(R.string.pg_mais_de, d.nomeDaTemporada ?: stringResource(R.string.pg_temporada)))
                }
                // Já rolada até este episódio.
                val estado = rememberLazyListState(temporada.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
                RolagemDeTvNasBordas(margem = 28.dp) {
                    LazyRow(state = estado, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(temporada, key = { it.id }) { episodio ->
                            val atual = episodio.id == item.id
                            Box(
                                if (atual) Modifier.border(2.dp, LabTheme.accent, RoundedCornerShape(12.dp)) else Modifier
                            ) {
                                CartaoDeitado(servidor, episodio, { if (!atual) onVideo(it) }) {}
                            }
                        }
                    }
                }
            }
        }
        val elenco = d.pessoasCom("Actor", "GuestStar").take(30)
        if (elenco.isNotEmpty()) item(key = "elenco") { FaixaDoElenco(servidor, elenco, onPessoa) }
        if (extras.isNotEmpty()) item(key = "extras") {
            Column(Modifier.padding(top = 22.dp)) {
                Box(Modifier.padding(horizontal = 16.dp)) { Secao(stringResource(R.string.pg_extras)) }
                RolagemDeTvNasBordas(margem = 28.dp) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(extras, key = { it.id }) { extra ->
                            Column(
                                Modifier.width(220.dp * Escala.fator()).tvFocus(12.dp).clip(RoundedCornerShape(12.dp))
                                    .clickable { tocarSozinho(extra) },
                            ) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
                                    ImagemDoServidor(servidor, extra.id, listOf("Primary", "Thumb"), 480, Modifier.fillMaxSize())
                                }
                                Text(extra.nome, color = LabTheme.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 7.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    when (seletor) {
        Seletores.AUDIO -> MenuDoItem(stringResource(R.string.pg_audio), { seletor = null }) {
            d.faixas("Audio").forEachIndexed { i, faixa ->
                OpcaoDoMenu(faixa.titulo, icone = if (faixa.indice == audio) Icons.Filled.Check else null, primeira = i == 0) {
                    audio = faixa.indice
                    seletor = null
                }
            }
        }
        Seletores.LEGENDA -> MenuDoItem(stringResource(R.string.pg_legendas), { seletor = null }) {
            OpcaoDoMenu(
                stringResource(R.string.pg_legenda_auto),
                icone = if (legenda == LegendaEscolhida.Automatica) Icons.Filled.Check else null, primeira = true,
            ) { legenda = LegendaEscolhida.Automatica; seletor = null }
            OpcaoDoMenu(
                stringResource(R.string.pg_legenda_desligada),
                icone = if (legenda == LegendaEscolhida.Desligada) Icons.Filled.Check else null,
            ) { legenda = LegendaEscolhida.Desligada; seletor = null }
            d.faixas("Subtitle").forEach { faixa ->
                OpcaoDoMenu(faixa.titulo, icone = if (legenda == LegendaEscolhida.Faixa(faixa.indice)) Icons.Filled.Check else null) {
                    legenda = LegendaEscolhida.Faixa(faixa.indice)
                    seletor = null
                }
            }
        }
        Seletores.QUALIDADE -> MenuDoItem(stringResource(R.string.pg_qualidade), { seletor = null }) {
            Qualidade.opcoes.forEachIndexed { i, kbps ->
                OpcaoDoMenu(Qualidade.nome(kbps), icone = if (kbps == qualidade) Icons.Filled.Check else null, primeira = i == 0) {
                    qualidade = kbps
                    Qualidade.definir(contexto, conta.id, kbps)
                    seletor = null
                }
            }
        }
        Seletores.MAIS -> MenuDoItem(item.titulo, { seletor = null }) {
            var primeira = true
            if (d.trailersNoDisco > 0 || d.trailersDaInternet.isNotEmpty()) {
                OpcaoDoMenu(stringResource(R.string.pg_trailer), primeira = primeira) {
                    seletor = null
                    escopo.launch {
                        val local = if (d.trailersNoDisco > 0) runCatching { servidor.trailersNoDisco(item.id) }.getOrNull()?.firstOrNull() else null
                        if (local != null) tocarSozinho(local)
                        else d.trailersDaInternet.firstOrNull()?.let { runCatching { links.openUri(it) } }
                    }
                }
                primeira = false
            }
            val serieId = item.serieId
            if (item.tipo == "Episode" && serieId != null) {
                OpcaoDoMenu(stringResource(R.string.pg_ir_para_serie), primeira = primeira) {
                    seletor = null
                    onSerie(serieId, item.serie.orEmpty())
                }
                primeira = false
            }
            d.idsExternos["Imdb"]?.let { imdb ->
                OpcaoDoMenu(stringResource(R.string.pg_abrir_imdb), primeira = primeira) {
                    seletor = null
                    runCatching { links.openUri("https://www.imdb.com/title/$imdb") }
                }
                primeira = false
            }
            if (item.tipo == "Movie") d.idsExternos["Tmdb"]?.let { tmdb ->
                OpcaoDoMenu(stringResource(R.string.pg_abrir_tmdb), primeira = primeira) {
                    seletor = null
                    runCatching { links.openUri("https://www.themoviedb.org/movie/$tmdb") }
                }
            }
        }
        null -> {}
    }
}

// MARK: - Página da série

/**
 * A página da série, como no app do Jellyfin: fundo e logotipo, sinopse, o
 * próximo episódio a ver no ▶, as temporadas em abas e os episódios da
 * escolhida, com cena, duração e o quanto já foi visto.
 */
@Composable
fun PaginaDaSerie(
    conta: ContaDeRede,
    servidor: ServidorDeMidia,
    serieId: String,
    nome: String,
    onEpisodio: (Item) -> Unit,
    onPessoa: (ServidorDeMidia.Pessoa) -> Unit,
) {
    val contexto = LocalContext.current
    val naTv = remember { Device.isTv(contexto) }
    val escopo = rememberCoroutineScope()
    val volta = rememberVoltaAoApp()
    var recarga by remember { mutableIntStateOf(0) }

    var serie by remember(serieId) { mutableStateOf<Item?>(null) }
    var detalhes by remember(serieId) { mutableStateOf<ServidorDeMidia.Detalhes?>(null) }
    var temporadas by remember(serieId) { mutableStateOf<List<Item>>(emptyList()) }
    var escolhida by remember(serieId) { mutableStateOf<String?>(null) }
    var episodios by remember(serieId) { mutableStateOf<List<Item>>(emptyList()) }
    var proximo by remember(serieId) { mutableStateOf<Item?>(null) }
    var erro by remember(serieId) { mutableStateOf<String?>(null) }
    val focoInicial = remember { FocusRequester() }

    LaunchedEffect(serieId, volta, recarga) {
        AvisoAoServidor.emDia()
        runCatching { servidor.detalhes(serieId) }
            .onSuccess { (item, lido) ->
                serie = item
                detalhes = lido
                erro = null
                temporadas = runCatching { servidor.temporadas(serieId) }.getOrDefault(emptyList())
                proximo = runCatching { servidor.proximoDaSerie(serieId) }.getOrNull()
                // Abre na temporada do próximo episódio a ver; sem ele, na primeira.
                if (escolhida == null || temporadas.none { it.id == escolhida }) {
                    escolhida = proximo?.temporadaId?.takeIf { id -> temporadas.any { it.id == id } } ?: temporadas.firstOrNull()?.id
                } else {
                    escolhida?.let { id -> runCatching { servidor.episodios(serieId, id) }.onSuccess { episodios = it } }
                }
            }
            .onFailure {
                if (it is CancellationException) throw it
                if (detalhes == null) erro = it.message ?: it.javaClass.simpleName
            }
    }
    LaunchedEffect(escolhida) {
        val id = escolhida ?: return@LaunchedEffect
        runCatching { servidor.episodios(serieId, id) }.onSuccess { episodios = it }
    }
    if (naTv) LaunchedEffect(detalhes != null) { runCatching { focoInicial.requestFocus() } }

    fun tocar(episodio: Item) {
        escopo.launch {
            val lista = runCatching { servidor.temporada(episodio) }.getOrDefault(listOf(episodio))
            Playback.start(contexto, paraTocar(conta, episodio), lista.map { paraTocar(conta, it) })
        }
    }

    fun marcar(acao: suspend () -> Unit) {
        escopo.launch {
            runCatching { acao() }
                .onSuccess { recarga++ }
                .onFailure {
                    if (it is CancellationException) throw it
                    Toast.makeText(contexto, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show()
                }
        }
    }

    val d = detalhes
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (erro != null) Text(erro!!, color = LabTheme.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            else CircularProgressIndicator()
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item(key = "cabeca") { Cabeca(servidor, d.fundoDe ?: serieId, d.logoDe) }
        item(key = "titulos") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(serie?.nome ?: nome, color = LabTheme.text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val partes = listOfNotNull(
                        serie?.ano?.toString(),
                        temporadas.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.pg_n_temporadas, it, it) },
                    )
                    Text(partes.joinToString("   "), color = LabTheme.muted, fontSize = 12.5.sp)
                    d.nota?.let {
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Filled.Star, null, tint = LabTheme.accent, modifier = Modifier.size(14.dp))
                        Text(String.format(java.util.Locale.ROOT, " %.1f", it), color = LabTheme.muted, fontSize = 12.5.sp)
                    }
                }
            }
        }
        item(key = "botoes") {
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BotaoDeIcone(
                        Icons.Filled.PlayArrow,
                        stringResource(if (proximo?.posicao != null) R.string.pg_continuar else R.string.pg_assistir),
                        Modifier.focusRequester(focoInicial), cor = LabTheme.accent, destaque = true,
                    ) { proximo?.let { tocar(it) } }
                    BotaoDeIcone(Icons.Filled.Check, stringResource(R.string.ja_reproduzido), cor = LabTheme.accent, aceso = serie?.visto == true) {
                        val novo = serie?.visto != true
                        marcar { servidor.marcarVisto(serieId, novo) }
                    }
                    BotaoDeIcone(
                        if (serie?.favorito == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        stringResource(R.string.pg_favorito), cor = LabTheme.red, aceso = serie?.favorito == true,
                    ) {
                        val novo = serie?.favorito != true
                        marcar { servidor.marcarFavorito(serieId, novo) }
                    }
                }
                proximo?.let { p ->
                    Text(
                        listOfNotNull(numeroDoEpisodio(p), p.nome).joinToString(" · "),
                        color = LabTheme.faint, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        d.sinopse?.let { item(key = "sinopse") { Box(Modifier.padding(horizontal = 16.dp)) { Sinopse(it) } } }
        if (d.generos.isNotEmpty()) item(key = "generos") {
            Text(
                d.generos.take(5).joinToString(" · "), color = LabTheme.muted, fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        if (temporadas.isNotEmpty()) item(key = "abas") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(temporadas, key = { it.id }) { t ->
                    val ativa = t.id == escolhida
                    Text(
                        t.nome, color = if (ativa) Color.Black else LabTheme.text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.tvFocus(17.dp).clip(RoundedCornerShape(17.dp))
                            .background(if (ativa) LabTheme.accent else LabTheme.glass)
                            .clickable { escolhida = t.id }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        items(episodios, key = { "e" + it.id }) { episodio ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .tvFocus(10.dp, scale = 1.02f).clip(RoundedCornerShape(10.dp))
                    .clickable { onEpisodio(episodio) },
            ) {
                Box(Modifier.width(150.dp * Escala.fator()).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                    ImagemDoServidor(servidor, episodio.id, listOf("Primary", "Thumb"), 400, Modifier.fillMaxSize())
                    if (episodio.visto) {
                        Icon(
                            Icons.Filled.Check, null, tint = Color.Black,
                            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp)
                                .clip(CircleShape).background(LabTheme.accent).padding(3.dp),
                        )
                    }
                    Progresso(episodio.progresso)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(episodio.titulo, color = LabTheme.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    episodio.duracao?.let { Text(TimeFormat.spoken(it), color = LabTheme.faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        val elenco = d.pessoasCom("Actor", "GuestStar").take(30)
        if (elenco.isNotEmpty()) item(key = "elenco") { FaixaDoElenco(servidor, elenco, onPessoa) }
    }
}

// MARK: - Página da pessoa

/** Foto, nascimento, biografia e os filmes e séries dela que estão no servidor. */
@Composable
fun PaginaDaPessoa(servidor: ServidorDeMidia, pessoa: ServidorDeMidia.Pessoa, onAbrir: (Item) -> Unit) {
    val contexto = LocalContext.current
    var detalhes by remember(pessoa.id) { mutableStateOf<ServidorDeMidia.Detalhes?>(null) }
    var obras by remember(pessoa.id) { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(pessoa.id) {
        detalhes = runCatching { servidor.detalhes(pessoa.id).second }.getOrNull()
        obras = runCatching { servidor.obras(pessoa.id) }.getOrDefault(emptyList())
    }
    val colunas = if (Device.isTv(contexto)) 6 else 3
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp)) {
        item(key = "topo") {
            Row {
                Box(Modifier.width(120.dp * Escala.fator()).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
                    ImagemDoServidor(servidor, pessoa.id, listOf("Primary"), 360, Modifier.fillMaxSize(), Icons.Filled.Person)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(pessoa.nome, color = LabTheme.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    detalhes?.estreia?.let {
                        Text(stringResource(R.string.pg_nascimento, DateFormat.getLongDateFormat(contexto).format(java.util.Date(it))), color = LabTheme.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    obras?.takeIf { it.isNotEmpty() }?.let {
                        Text(pluralStringResource(R.plurals.pg_n_titulos, it.size, it.size), color = LabTheme.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        detalhes?.sinopse?.let { item(key = "bio") { Box(Modifier.padding(vertical = 16.dp)) { Sinopse(it) } } }
        val lista = obras
        if (lista == null) {
            item(key = "lendo") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else if (lista.isNotEmpty()) {
            item(key = "titulo") { Box(Modifier.padding(top = 8.dp)) { Secao(stringResource(R.string.pg_no_servidor)) } }
            items(lista.chunked(colunas), key = { linha -> "l" + linha.first().id }) { linha ->
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    linha.forEach { obra -> Box(Modifier.weight(1f)) { Cartaz(servidor, obra, onAbrir) {} } }
                    repeat(colunas - linha.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// MARK: - TV ao vivo

/**
 * Abre um canal e começa a tocar. O servidor abre a transmissão antes (ver
 * [ServidorDeMidia.abrirCanal]); o endereço fica em [Conversoes], onde o
 * player o procura. Ao vivo não tem "onde parou": entra sempre no agora.
 */
suspend fun tocarCanal(contexto: android.content.Context, conta: ContaDeRede, servidor: ServidorDeMidia, canal: ServidorDeMidia.Canal) {
    val (uri, sessao, transmissao) = servidor.abrirCanal(canal.id)
    Conversoes.registrarCanal(canal.id, uri, sessao, transmissao)
    val origem = MediaOrigin.Midia(conta.id, canal.id)
    val item = MediaItem(id = origem.resumeKey, title = canal.nome, origin = origem)
    EscolhaDeFaixas.pedirInicio(EscolhaDeFaixas.Inicio.DO_COMECO, origem.resumeKey)
    Playback.start(contexto, item, listOf(item))
}

/** O logotipo do canal inteiro sobre o cartão escuro — logotipo de emissora é largo e não pode ser cortado. */
@Composable
private fun LogoDoCanal(servidor: ServidorDeMidia, canal: ServidorDeMidia.Canal, modifier: Modifier) {
    val imagem by produceState<Bitmap?>(null, canal.id) {
        value = (servidor.baixarImagem(canal.id, listOf("Primary"), 320) as? ServidorDeMidia.ImagemBaixada.Pronta)?.bitmap
    }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF232228), Color(0xFF16161A)))), contentAlignment = Alignment.Center) {
        val pronta = imagem
        if (pronta != null) {
            Image(pronta.asImageBitmap(), null, Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
        } else {
            Text(canal.numero ?: canal.nome.take(3), color = LabTheme.faint, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/** "AO VIVO" em vermelho, como em toda TV. */
@Composable
private fun SeloAoVivo(modifier: Modifier) {
    Text(
        stringResource(R.string.ao_vivo), color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(LabTheme.red).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** O cartão da faixa do início do servidor: o logotipo, o canal e o que está passando. */
@Composable
internal fun CartaoDeCanal(servidor: ServidorDeMidia, canal: ServidorDeMidia.Canal, onClick: () -> Unit) {
    Column(Modifier.width(200.dp * Escala.fator()).tvFocus(12.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).border(0.5.dp, LabTheme.glassBorder, RoundedCornerShape(12.dp))) {
            LogoDoCanal(servidor, canal, Modifier.fillMaxSize())
            SeloAoVivo(Modifier.align(Alignment.TopStart).padding(6.dp))
            Progresso(canal.programa?.andamento())
        }
        Text(
            listOfNotNull(canal.numero, canal.nome).joinToString(" · "), color = LabTheme.text, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
        )
        Text(canal.programa?.nome ?: " ", color = LabTheme.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Todos os canais, com o programa de agora, o horário e quanto já passou. */
@Composable
fun PaginaDosCanais(conta: ContaDeRede, servidor: ServidorDeMidia) {
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()
    var canais by remember { mutableStateOf<List<ServidorDeMidia.Canal>?>(null) }
    var abrindo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(conta.id) { canais = runCatching { servidor.canais() }.getOrDefault(emptyList()) }

    val lista = canais
    when {
        lista == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        lista.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.nenhum_canal), color = LabTheme.muted)
        }
        else -> RolagemDeTvNasBordas(margem = 28.dp) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 110.dp)) {
                items(lista, key = { it.id }) { canal ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).tvFocus(10.dp, scale = 1.02f).clip(RoundedCornerShape(10.dp))
                            .clickable {
                                if (abrindo != null) return@clickable
                                abrindo = canal.id
                                escopo.launch {
                                    runCatching { tocarCanal(contexto, conta, servidor, canal) }.onFailure {
                                        if (it is CancellationException) throw it
                                        Toast.makeText(contexto, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show()
                                    }
                                    abrindo = null
                                }
                            }
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LogoDoCanal(servidor, canal, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(listOfNotNull(canal.numero, canal.nome).joinToString(" · "), color = LabTheme.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            canal.programa?.let { p ->
                                Text(p.nome, color = LabTheme.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p.inicio != null && p.fim != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${hora(contexto, p.inicio)} – ${hora(contexto, p.fim)}", color = LabTheme.faint, fontSize = 11.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Box(Modifier.width(90.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                                            Box(Modifier.fillMaxHeight().fillMaxWidth(p.andamento() ?: 0f).background(LabTheme.accent))
                                        }
                                    }
                                }
                            }
                        }
                        if (abrindo == canal.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}
