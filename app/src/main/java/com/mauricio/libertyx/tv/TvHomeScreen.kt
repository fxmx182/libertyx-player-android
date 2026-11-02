package com.mauricio.libertyx.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.ResumeStore
import com.mauricio.libertyx.core.TimeFormat
import com.mauricio.libertyx.core.VideoGroup
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.library.MediaLibrary
import com.mauricio.libertyx.library.Thumbnails
import com.mauricio.libertyx.player.Playback
import com.mauricio.libertyx.core.Historico
import com.mauricio.libertyx.library.rememberVoltaAoApp
import com.mauricio.libertyx.rede.AtalhoDeServidor
import com.mauricio.libertyx.R
import androidx.compose.ui.res.stringResource
import com.mauricio.libertyx.core.Demonstracao
import androidx.compose.ui.res.painterResource

/**
 * A tela inicial na televisão.
 *
 * Três diferenças que não são de estilo, e sim de uso:
 *
 * 1. **O servidor vem primeiro.** Num celular a biblioteca local é o que
 *    importa; numa caixinha de TV quase não existe vídeo local — o acervo mora
 *    no servidor de casa. Enterrar isso atrás de um ícone de canto seria
 *    esconder justamente o que a pessoa ligou a TV para ver.
 * 2. **Faixas na horizontal**, e não grade. É como todo aparelho de TV
 *    organiza conteúdo, e é o que o controle remoto sabe percorrer: esquerda e
 *    direita dentro de um assunto, cima e baixo entre assuntos.
 * 3. **Margem de segurança nas bordas.** Muita TV ainda corta uns 5% da
 *    imagem; texto encostado na borda simplesmente não aparece.
 */
@Composable
fun TvHomeScreen(onOpenServers: () -> Unit, onAtalho: (AtalhoDeServidor) -> Unit) {
    val contexto = LocalContext.current
    // Relidos a cada volta: de um vídeo (o histórico muda) ou do cadastro
    // de servidores (um novo aparece aqui na hora).
    val volta = rememberVoltaAoApp()
    val atalhos = remember(volta) { AtalhoDeServidor.todos(contexto) }
    val versaoDoHistorico = Historico.versao.intValue
    val historico = remember(versaoDoHistorico, volta) { Historico.get(contexto).itens.take(20) }
    val primeiroFoco = remember { FocusRequester() }

    val grupos by produceState<List<VideoGroup>?>(initialValue = null) {
        value = MediaLibrary.scan(contexto)
    }

    // Sem isto o controle remoto começa sem foco em lugar nenhum: a primeira
    // seta não move nada e parece que a TV travou.
    LaunchedEffect(Unit) { runCatching { primeiroFoco.requestFocus() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(LabTheme.background),
        contentPadding = PaddingValues(top = 12.dp, bottom = OVERSCAN_V),
        verticalArrangement = Arrangement.spacedBy(34.dp),
    ) {
        // O que foi visto por último, de qualquer origem — o mesmo histórico
        // do celular. Vem primeiro: é o que se quer ao ligar a TV de novo.
        if (historico.isNotEmpty()) {
            item(key = "historico") {
                Faixa(stringResource(R.string.historico)) {
                    items(historico, key = { it.origin.resumeKey }) { item ->
                        CartaoVideo(
                            item = item,
                            modifier = if (item === historico.first()) Modifier.focusRequester(primeiroFoco) else Modifier,
                            volta = volta,
                        ) { Playback.start(contexto, item, historico) }
                    }
                }
            }
        }

        // Todos os servidores salvos, e não só os SMB: cada um abre direto no
        // servidor, como no celular. Cadastrar e editar ficam no último cartão.
        item(key = "servidores") {
            Faixa(stringResource(R.string.servidores)) {
                items(atalhos, key = { it.chave }) { atalho ->
                    CartaoServidor(
                        atalho,
                        if (historico.isEmpty() && atalho === atalhos.first()) Modifier.focusRequester(primeiroFoco) else Modifier,
                    ) { onAtalho(atalho) }
                }
                item {
                    CartaoAcao(
                        titulo = stringResource(if (atalhos.isEmpty()) R.string.conectar_servidor else R.string.gerenciar_servidores),
                        modifier = if (historico.isEmpty() && atalhos.isEmpty()) Modifier.focusRequester(primeiroFoco) else Modifier,
                        onClick = onOpenServers,
                    )
                }
            }
        }

        val locais = grupos
        if (locais != null) {
            items(locais, key = { it.path }) { grupo ->
                Faixa(grupo.name.uppercase()) {
                    val ordenados = grupo.items
                    items(ordenados, key = { it.id }) { item ->
                        CartaoVideo(item = item, volta = volta) { Playback.start(contexto, item, ordenados) }
                    }
                }
            }
            if (locais.isEmpty()) {
                item { Vazio() }
                item {
                    Faixa(stringResource(R.string.demonstracao)) {
                        item {
                            CartaoVideo(
                                item = Demonstracao.item,
                                capa = R.drawable.demo_sintel,
                                legenda = Demonstracao.CREDITO,
                            ) { Playback.start(contexto, Demonstracao.item, emptyList()) }
                        }
                    }
                }
            }
        }

    }
}

private val OVERSCAN_H = 48.dp
private val OVERSCAN_V = 27.dp

/** Uma faixa: título à esquerda e os cartões correndo para a direita. */
@Composable
private fun Faixa(titulo: String, conteudo: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(
            titulo,
            color = LabTheme.faint,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
            modifier = Modifier.padding(start = OVERSCAN_H, bottom = 12.dp),
        )
        LazyRow(
            // O recuo grande à esquerda e à direita deixa o cartão focado
            // crescer sem encostar na borda da tela.
            contentPadding = PaddingValues(horizontal = OVERSCAN_H),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            conteudo()
        }
    }
}

private val CARD_W = 232.dp
private val CARD_H = 130.dp

@Composable
private fun CartaoVideo(
    item: MediaItem,
    modifier: Modifier = Modifier,
    capa: Int? = null,
    legenda: String? = null,
    volta: Int = 0,
    onClick: () -> Unit,
) {
    val contexto = LocalContext.current
    val imagem by produceState<android.graphics.Bitmap?>(
        initialValue = Thumbnails.cached(item), key1 = item.id,
    ) {
        if (value == null) value = Thumbnails.load(contexto, item)
    }

    Column(modifier.width(CARD_W)) {
        Box(
            Modifier
                .width(CARD_W).height(CARD_H)
                .tvFocus()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val pronta = imagem
            if (capa != null) {
                Image(
                    painter = painterResource(capa),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else if (pronta != null) {
                Image(
                    bitmap = pronta.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Filled.Movie, null, tint = LabTheme.faint, modifier = Modifier.size(30.dp))
            }

            item.duration?.let { duracao ->
                Text(
                    TimeFormat.clock(duracao),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }

        Spacer(Modifier.height(9.dp))
        Text(
            item.title,
            color = LabTheme.text,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val retomada = remember(item.id, volta) {
            ResumeStore.get(contexto).position(item.origin.resumeKey)
        }
        Text(
            if (retomada != null) stringResource(R.string.parou_em, TimeFormat.clock(retomada)) else legenda ?: " ",
            color = if (retomada != null) LabTheme.accent else LabTheme.muted,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun CartaoServidor(atalho: AtalhoDeServidor, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.width(CARD_W)) {
        Box(
            Modifier
                .width(CARD_W).height(CARD_H)
                .tvFocus()
                .clip(RoundedCornerShape(14.dp))
                .background(LabTheme.glass)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(atalho.icone, null, tint = LabTheme.accent, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(atalho.nome, color = LabTheme.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val detalhe = when (atalho) {
            is AtalhoDeServidor.Smb -> atalho.servidor.displayHost
            is AtalhoDeServidor.Conta -> atalho.tipo
        }
        Text(detalhe, color = LabTheme.muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun CartaoAcao(
    titulo: String,
    modifier: Modifier = Modifier,
    icone: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Add,
    onClick: () -> Unit,
) {
    Column(modifier.width(CARD_W)) {
        Box(
            Modifier
                .width(CARD_W).height(CARD_H)
                .tvFocus()
                .clip(RoundedCornerShape(14.dp))
                .background(LabTheme.glass)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icone, null, tint = LabTheme.accent, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(titulo, color = LabTheme.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Vazio() {
    Column(Modifier.fillMaxWidth().padding(horizontal = OVERSCAN_H, vertical = 20.dp)) {
        Text(stringResource(R.string.tv_sem_videos), color = LabTheme.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.tv_sem_videos_explica),
            color = LabTheme.muted,
            fontSize = 14.sp,
        )
    }
}
