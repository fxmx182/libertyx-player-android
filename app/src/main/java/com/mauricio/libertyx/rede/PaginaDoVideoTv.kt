package com.mauricio.libertyx.rede

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.TimeFormat
import com.mauricio.libertyx.library.Secao
import com.mauricio.libertyx.rede.ServidorDeMidia.Item
import com.mauricio.libertyx.tv.RolagemDeTvNasBordas
import com.mauricio.libertyx.tv.tvFocus
import kotlinx.coroutines.delay

/**
 * A página do filme ou do episódio na TV, com a cara da do app de TV do
 * Jellyfin: o fundo da série desfocado tomando a tela, o logotipo no canto,
 * o nome grande, a nota, o episódio, a data e os selos do arquivo (CC, 1080p,
 * HEVC, DD+, STEREO), à esquerda quem dirigiu, a duração e a que horas
 * termina, a sinopse, e uma fileira de botões quadrados com o nome embaixo —
 * Retomar, Reproduzir, Assistido, Episódio anterior, Outras opções. Abaixo,
 * os próximos episódios, o elenco e os extras.
 *
 * Escrita aqui, do zero: do app deles vem só a arrumação da tela.
 */
@Composable
internal fun PaginaDoVideoNaTv(
    servidor: ServidorDeMidia,
    item: Item,
    d: ServidorDeMidia.Detalhes,
    temporada: List<Item>,
    extras: List<Item>,
    focoInicial: FocusRequester,
    onTocar: (continuar: Boolean?) -> Unit,
    onVisto: () -> Unit,
    onFavorito: () -> Unit,
    onMais: () -> Unit,
    onVideo: (Item) -> Unit,
    onPessoa: (ServidorDeMidia.Pessoa) -> Unit,
    onExtra: (Item) -> Unit,
) {
    val contexto = LocalContext.current
    val tela = LocalConfiguration.current
    var agora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            agora = System.currentTimeMillis()
        }
    }
    // A barra de abas do alto some enquanto esta página está aberta.
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.mauricio.libertyx.library.TelaCheiaNaTv.ativa.value = true
        onDispose { com.mauricio.libertyx.library.TelaCheiaNaTv.ativa.value = false }
    }
    val irAoInicio = com.mauricio.libertyx.library.LocalIrAoInicio.current
    val posicaoNaTemporada = temporada.indexOfFirst { it.id == item.id }
    val anterior = temporada.getOrNull(posicaoNaTemporada - 1)?.takeIf { posicaoNaTemporada > 0 }
    val seguintes = if (posicaoNaTemporada >= 0) temporada.drop(posicaoNaTemporada + 1) else emptyList()

    Box(Modifier.fillMaxSize().background(LabTheme.background)) {
        // O fundo desfocado e escurecido: dá a cor do filme à tela sem
        // brigar com o texto por cima.
        ImagemDoServidor(
            servidor, d.fundoDe ?: item.serieId ?: item.id, listOf("Backdrop", "Thumb", "Primary"), 1280,
            Modifier.fillMaxSize().blur(18.dp),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.78f), 0.6f to Color.Black.copy(alpha = 0.5f), 1f to Color.Black.copy(alpha = 0.35f),
                )
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))


        RolagemDeTvNasBordas(margem = 40.dp) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                item(key = "topo") {
                    Box(Modifier.fillMaxWidth().heightIn(min = (tela.screenHeightDp * 0.74f).dp)) {
                        // No canto, só a casinha (volta ao Início) e a hora —
                        // a barra de abas fica escondida nesta página.
                        Row(
                            Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(44.dp).tvFocus(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .clickable(onClick = irAoInicio),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Home, null, tint = LabTheme.text, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(20.dp))
                            Text(
                                DateFormat.getTimeFormat(contexto).format(java.util.Date(agora)),
                                color = LabTheme.text, fontSize = 22.sp, fontWeight = FontWeight.Light,
                            )
                        }
                        d.logoDe?.let {
                            LogoDoServidor(
                                servidor, it,
                                Modifier.align(Alignment.TopEnd).padding(top = 170.dp, end = 48.dp).widthIn(max = 260.dp).heightIn(max = 70.dp),
                            )
                        }
                        // A margem da direita deixa lugar ao logotipo; os botões passam por baixo dele.
                        Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 80.dp)) {
                            Text(
                                if (item.tipo == "Episode") item.nome else item.nome,
                                color = LabTheme.text, fontSize = 34.sp, fontWeight = FontWeight.Light,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 140.dp, end = 292.dp),
                            )
                            // Até antes do logotipo: os selos não passam por baixo dele.
                            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                            androidx.compose.foundation.layout.FlowRow(
                                Modifier.padding(start = 140.dp, top = 6.dp, end = 292.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                d.nota?.let {
                                    Icon(Icons.Filled.Star, null, tint = LabTheme.accent, modifier = Modifier.size(18.dp))
                                    Text(String.format(java.util.Locale.getDefault(), " %.1f", it), color = LabTheme.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(12.dp))
                                }
                                numeroDoEpisodio(item)?.let {
                                    Text(it, color = LabTheme.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(12.dp))
                                }
                                val data = d.estreia?.let { DateFormat.getMediumDateFormat(contexto).format(java.util.Date(it)) }
                                    ?: item.ano?.toString()
                                data?.let {
                                    Text(it, color = LabTheme.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(10.dp))
                                }
                                selos(d).forEach { Selo(it) }
                            }
                            Row(Modifier.padding(top = 18.dp, end = 292.dp)) {
                                // A coluna da esquerda, alinhada à direita: rótulo em cima, valor embaixo.
                                Column(Modifier.width(128.dp), horizontalAlignment = Alignment.End) {
                                    d.pessoasCom("Director").firstOrNull()?.let {
                                        Rotulo(stringResource(R.string.tv_dirigido_por), it.nome)
                                    }
                                    item.duracao?.let { Rotulo(stringResource(R.string.tv_duracao), TimeFormat.spoken(it)) }
                                    item.duracao?.let { duracao ->
                                        val fim = agora + ((duracao - (item.posicao ?: 0.0)) * 1000).toLong()
                                        Rotulo(stringResource(R.string.tv_finaliza_as), DateFormat.getTimeFormat(contexto).format(java.util.Date(fim)))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    d.sinopse?.let {
                                        Text(it, color = LabTheme.text.copy(alpha = 0.9f), fontSize = 17.sp, lineHeight = 23.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            Row(Modifier.padding(start = 140.dp, top = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                val posicao = item.posicao
                                if (posicao != null) {
                                    BotaoDaTv(Icons.Filled.SkipNext, stringResource(R.string.tv_retomar_de, TimeFormat.clock(posicao)), Modifier.focusRequester(focoInicial)) {
                                        onTocar(true)
                                    }
                                    BotaoDaTv(Icons.Filled.Replay, stringResource(R.string.tv_do_inicio)) { onTocar(false) }
                                } else {
                                    BotaoDaTv(Icons.Filled.PlayArrow, stringResource(R.string.tv_reproduzir), Modifier.focusRequester(focoInicial)) {
                                        onTocar(null)
                                    }
                                }
                                BotaoDaTv(Icons.Filled.Check, stringResource(R.string.tv_assistido), aceso = item.visto, onClick = onVisto)
                                BotaoDaTv(
                                    if (item.favorito) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    stringResource(R.string.pg_favorito), aceso = item.favorito, cor = LabTheme.red, onClick = onFavorito,
                                )
                                anterior?.let { BotaoDaTv(Icons.Filled.ChevronLeft, stringResource(R.string.tv_episodio_anterior)) { onVideo(it) } }
                                BotaoDaTv(Icons.Filled.MoreHoriz, stringResource(R.string.tv_outras_opcoes), onClick = onMais)
                            }
                        }
                    }
                }
                if (seguintes.isNotEmpty()) item(key = "seguintes") {
                    Faixa(stringResource(R.string.tv_proximos)) {
                        items(seguintes, key = { it.id }) { episodio -> CartaoDeitado(servidor, episodio, onVideo) {} }
                    }
                }
                val elenco = d.pessoasCom("Actor", "GuestStar").take(30)
                if (elenco.isNotEmpty()) item(key = "elenco") {
                    Faixa(stringResource(R.string.pg_elenco)) {
                        items(elenco, key = { it.id + it.tipo + (it.papel ?: "") }) { pessoa ->
                            Column(
                                Modifier.width(110.dp).tvFocus(10.dp).clip(RoundedCornerShape(10.dp)).clickable { onPessoa(pessoa) },
                            ) {
                                Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp))) {
                                    ImagemDoServidor(servidor, pessoa.id, listOf("Primary"), 240, Modifier.fillMaxSize(), androidx.compose.material.icons.Icons.Filled.Star)
                                }
                                Text(pessoa.nome, color = LabTheme.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                                pessoa.papel?.let { Text(it, color = LabTheme.muted, fontSize = 12.sp, maxLines = 1) }
                            }
                        }
                    }
                }
                if (extras.isNotEmpty()) item(key = "extras") {
                    Faixa(stringResource(R.string.pg_extras)) {
                        items(extras, key = { it.id }) { extra -> CartaoDeitado(servidor, extra, onExtra) {} }
                    }
                }
            }
        }
    }
}

@Composable
private fun Faixa(titulo: String, conteudo: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(titulo, color = LabTheme.text, fontSize = 22.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(start = 48.dp, bottom = 10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), content = conteudo)
    }
}

@Composable
private fun Rotulo(rotulo: String, valor: String) {
    Text(rotulo, color = LabTheme.text.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    Text(valor, color = LabTheme.text, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.padding(bottom = 14.dp))
}

@Composable
private fun Selo(texto: String) {
    Text(
        texto, color = LabTheme.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** CC, a resolução, o codec, o HDR, o som — lidos das faixas do arquivo. */
private fun selos(d: ServidorDeMidia.Detalhes): List<String> {
    val video = d.faixas("Video").firstOrNull()
    val audio = d.faixas("Audio").let { a -> a.firstOrNull { it.padrao } ?: a.firstOrNull() }
    return buildList {
        if (d.faixas("Subtitle").isNotEmpty()) add("CC")
        video?.altura?.takeIf { it > 0 }?.let {
            add(
                when {
                    it >= 2000 -> "4K"
                    it >= 1000 -> "1080p"
                    it >= 700 -> "720p"
                    else -> "${it}p"
                }
            )
        }
        video?.codec?.takeIf { it.isNotEmpty() }?.let { add(if (it.equals("hevc", true) || it.equals("h265", true)) "HEVC" else it.uppercase()) }
        video?.faixaDinamica?.takeIf { it.isNotEmpty() && !it.equals("SDR", true) }?.let {
            add(
                when (it.uppercase()) {
                    "HDR10PLUS" -> "HDR10+"
                    "DOVI", "DOVIWITHHDR10" -> "DOLBY VISION"
                    else -> it.uppercase()
                }
            )
        }
        audio?.codec?.takeIf { it.isNotEmpty() }?.let {
            add(
                when (it.lowercase()) {
                    "eac3" -> "DD+"
                    "ac3" -> "DD"
                    "truehd" -> "TRUEHD"
                    "dts" -> "DTS"
                    else -> it.uppercase()
                }
            )
        }
        audio?.canais?.takeIf { it > 0 }?.let {
            add(
                when (it) {
                    1 -> "MONO"
                    2 -> "STEREO"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> "$it CH"
                }
            )
        }
    }
}

/**
 * O botão quadrado com o nome embaixo. Focado, ele acende — fundo claro,
 * ícone escuro —, que é o que se lê da poltrona.
 */
@Composable
private fun BotaoDaTv(
    icone: ImageVector,
    rotulo: String,
    modifier: Modifier = Modifier,
    aceso: Boolean = false,
    cor: Color = LabTheme.accent,
    onClick: () -> Unit,
) {
    var focado by remember { mutableStateOf(false) }
    Column(modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(width = 76.dp, height = 46.dp)
                .onFocusChanged { focado = it.isFocused }
                .clip(RoundedCornerShape(6.dp))
                .background(if (focado) Color.White else Color.White.copy(alpha = 0.2f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icone, rotulo,
                tint = when {
                    focado -> Color.Black
                    aceso -> cor
                    else -> LabTheme.text
                },
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            rotulo, color = if (focado) LabTheme.text else LabTheme.text.copy(alpha = 0.8f), fontSize = 13.sp,
            textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
