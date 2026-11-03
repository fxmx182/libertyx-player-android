package com.mauricio.libertyx.rede

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.Escala
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.TimeFormat
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.library.Secao
import com.mauricio.libertyx.library.rememberVoltaAoApp
import com.mauricio.libertyx.player.Playback
import com.mauricio.libertyx.tv.tvFocus
import com.mauricio.libertyx.tv.cliqueComOpcoes
import com.mauricio.libertyx.tv.RolagemDeTv
import com.mauricio.libertyx.library.MenuDoItem
import com.mauricio.libertyx.library.OpcaoDoMenu
import com.mauricio.libertyx.core.ResumeStore
import androidx.compose.runtime.mutableIntStateOf
import android.widget.Toast
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import com.mauricio.libertyx.rede.ServidorDeMidia.Item

/**
 * A primeira tela de um Jellyfin ou Emby, como no app do próprio servidor: o
 * que ficou pela metade, o próximo episódio de cada série e o que chegou por
 * último em cada biblioteca.
 *
 * As pastas continuam a um toque — nas bibliotecas e no "›" de cada faixa —
 * para quem prefere procurar pelo catálogo inteiro.
 */
@Composable
fun InicioDoServidor(conta: ContaDeRede, onAbrir: (Item) -> Unit) {
    val contexto = LocalContext.current
    val servidor = remember(conta.id) {
        val loja = ContasDeRede.get(contexto)
        ServidorDeMidia(conta, loja.segredo(conta))
    }
    val volta = rememberVoltaAoApp()
    var inicio by remember(conta.id) { mutableStateOf<Inicio?>(null) }
    var erro by remember(conta.id) { mutableStateOf<String?>(null) }
    // Sobe quando um item é marcado ou desmarcado como visto.
    var recarga by remember { mutableIntStateOf(0) }
    var menuDe by remember { mutableStateOf<Item?>(null) }
    // O cartão marcado como visto pode sair da faixa levando o foco junto, e
    // a TV o largaria na seta de voltar. Ele vai para o primeiro cartão.
    val topo = remember { FocusRequester() }
    var refocar by remember { mutableStateOf(false) }
    LaunchedEffect(inicio) {
        if (refocar) {
            refocar = false
            runCatching { topo.requestFocus() }
        }
    }

    // De novo a cada volta do player: o filme que acabou de ser visto sai do
    // "Continuar", e o episódio seguinte entra no "A seguir". A tela antiga
    // fica enquanto a nova carrega, sem piscar.
    LaunchedEffect(conta.id, volta, recarga) {
        runCatching { carregar(servidor) }
            .onSuccess { inicio = it; erro = null }
            .onFailure { if (inicio == null) erro = it.message ?: it.javaClass.simpleName }
    }

    val escopo = rememberCoroutineScope()
    var abrindo by remember { mutableStateOf(false) }
    val abrir: (Item) -> Unit = abrir@{ item ->
        if (!item.ehVideo) {
            onAbrir(item)
            return@abrir
        }
        if (abrindo) return@abrir
        abrindo = true
        escopo.launch {
            // Episódio toca com a temporada em volta, para o "próximo" do
            // player ir ao episódio seguinte, e não ao vizinho da faixa.
            val fila = if (item.tipo == "Episode") {
                runCatching { servidor.temporada(item) }.getOrNull()
                    ?.takeIf { lista -> lista.any { it.id == item.id } } ?: listOf(item)
            } else listOf(item)
            abrindo = false
            Playback.start(contexto, paraTocar(conta, item), fila.map { paraTocar(conta, it) })
        }
    }

    Box(Modifier.fillMaxSize()) {
        val pronto = inicio
        when {
            pronto == null && erro != null -> Text(
                erro!!,
                color = LabTheme.muted, textAlign = TextAlign.Center, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            pronto == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            pronto.bibliotecas.isEmpty() -> Text(
                stringResource(R.string.pasta_vazia), color = LabTheme.muted,
                modifier = Modifier.align(Alignment.Center),
            )
            // Na TV a faixa focada sobe para o alto, logo abaixo do título dela.
            else -> RolagemDeTv(inicio = 52.dp) { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)) {
                if (pronto.continuar.isNotEmpty()) item(key = "continuar") {
                    Faixa(stringResource(R.string.continuar_assistindo)) {
                        items(pronto.continuar, key = { it.id }) {
                            CartaoDeitado(servidor, it, abrir, noTopo(it === pronto.continuar.first(), topo)) { menuDe = it }
                        }
                    }
                }
                if (pronto.aSeguir.isNotEmpty()) item(key = "aSeguir") {
                    Faixa(stringResource(R.string.a_seguir)) {
                        items(pronto.aSeguir, key = { it.id }) {
                            val primeiro = pronto.continuar.isEmpty() && it === pronto.aSeguir.first()
                            CartaoDeitado(servidor, it, abrir, noTopo(primeiro, topo)) { menuDe = it }
                        }
                    }
                }
                item(key = "bibliotecas") {
                    Faixa(stringResource(R.string.bibliotecas)) {
                        items(pronto.bibliotecas, key = { it.id }) {
                            val primeiro = pronto.continuar.isEmpty() && pronto.aSeguir.isEmpty() && it === pronto.bibliotecas.first()
                            CartaoDeBiblioteca(servidor, it, onAbrir, noTopo(primeiro, topo))
                        }
                    }
                }
                pronto.recentes.forEach { (biblioteca, itens) ->
                    item(key = "r" + biblioteca.id) {
                        Faixa(stringResource(R.string.recentes_em, biblioteca.nome), onTitulo = { onAbrir(biblioteca) }) {
                            items(itens, key = { it.id }) { Cartaz(servidor, it, abrir) { menuDe = it } }
                        }
                    }
                }
            } }
        }
    }

    menuDe?.let { alvo ->
        MenuDoVisto(servidor, conta, alvo, onFechar = { menuDe = null }, onMudou = { refocar = true; recarga++ })
    }
}

/**
 * Segurar um vídeo, uma série ou uma temporada do Jellyfin ou do Emby: a
 * caixinha "Já reproduzido", como no app do servidor. A marca muda na hora;
 * o diálogo fecha quando o servidor confirma, e ela volta atrás se ele recusar.
 */
@Composable
internal fun MenuDoVisto(
    servidor: ServidorDeMidia,
    conta: ContaDeRede,
    item: Item,
    onFechar: () -> Unit,
    onMudou: () -> Unit,
) {
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()
    var visto by remember(item.id) { mutableStateOf(item.visto) }
    var enviando by remember(item.id) { mutableStateOf(false) }
    MenuDoItem(item.titulo, onFechar) {
        OpcaoDoMenu(stringResource(R.string.ja_reproduzido), marcado = visto, primeira = true) {
            if (enviando) return@OpcaoDoMenu
            enviando = true
            val novo = !visto
            visto = novo
            escopo.launch {
                runCatching { servidor.marcarVisto(item.id, novo) }
                    .onSuccess {
                        // O servidor zera onde parou nos dois sentidos; a marca
                        // daqui segue, ou o cartão voltaria com a barrinha.
                        ResumeStore.get(contexto).clear(MediaOrigin.Midia(conta.id, item.id).resumeKey)
                        onMudou()
                        onFechar()
                    }
                    .onFailure {
                        visto = !novo
                        enviando = false
                        Toast.makeText(contexto, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show()
                    }
            }
        }
    }
}

private fun noTopo(primeiro: Boolean, topo: FocusRequester): Modifier =
    if (primeiro) Modifier.focusRequester(topo) else Modifier

private class Inicio(
    val continuar: List<Item>,
    val aSeguir: List<Item>,
    val bibliotecas: List<Item>,
    val recentes: List<Pair<Item, List<Item>>>,
)

/**
 * Tudo junto, em paralelo. Só as bibliotecas derrubam a tela quando falham —
 * é o pedido que diz se o servidor está lá e aceitou a conta. Uma faixa que
 * falha sozinha só não aparece.
 */
private suspend fun carregar(servidor: ServidorDeMidia): Inicio = coroutineScope {
    // Quem acabou de fechar o player chega aqui antes de o "parou" chegar ao
    // servidor; sem esperar, o filme que terminou voltaria ao "Continuar".
    AvisoAoServidor.emDia()
    val continuar = async { runCatching { servidor.continuar() }.getOrDefault(emptyList()) }
    val aSeguir = async { runCatching { servidor.aSeguir() }.getOrDefault(emptyList()) }
    val bibliotecas = servidor.bibliotecas()
    val recentes = bibliotecas
        // Coleções só repetem filmes que já aparecem na biblioteca deles.
        .filter { it.tipoDeColecao != "boxsets" }
        .map { b -> async { b to runCatching { servidor.recentes(b) }.getOrDefault(emptyList()) } }
    val comecados = continuar.await().map { it.id }.toSet()
    Inicio(
        continuar = continuar.await(),
        // O servidor põe no "A seguir" o episódio que já está no "Continuar".
        aSeguir = aSeguir.await().filter { it.id !in comecados },
        bibliotecas = bibliotecas,
        recentes = recentes.awaitAll().filter { it.second.isNotEmpty() },
    )
}

/** O mesmo título das pastas do servidor, para a retomada e o histórico baterem. */
internal fun paraTocar(conta: ContaDeRede, item: Item): MediaItem {
    val origem = MediaOrigin.Midia(conta.id, item.id)
    return MediaItem(
        id = origem.resumeKey, title = item.titulo, origin = origem,
        modifiedAt = item.criado, duration = item.duracao,
    )
}

/** Um título e, abaixo, a fila de capas que rola de lado. */
@Composable
private fun Faixa(titulo: String, onTitulo: (() -> Unit)? = null, conteudo: LazyListScope.() -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        val cabecalho = Modifier.padding(horizontal = 16.dp)
        Row(
            if (onTitulo == null) cabecalho
            else cabecalho.tvFocus(8.dp, scale = 1.02f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onTitulo),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Secao(titulo)
            if (onTitulo != null) {
                // O mesmo recuo de baixo da seção, para a seta ficar na altura do texto.
                Icon(
                    Icons.Filled.ChevronRight, null, tint = LabTheme.muted,
                    modifier = Modifier.padding(start = 2.dp, bottom = 8.dp).size(18.dp),
                )
            }
        }
        RolagemDeTv(inicio = 16.dp) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = conteudo,
            )
        }
    }
}

/** "Continuar" e "A seguir": a cena deitada, com o quanto falta. */
@Composable
private fun CartaoDeitado(
    servidor: ServidorDeMidia,
    item: Item,
    onClick: (Item) -> Unit,
    modifier: Modifier = Modifier,
    onOpcoes: (Item) -> Unit,
) {
    val forma = RoundedCornerShape(12.dp)
    Column(
        modifier.width(236.dp * Escala.fator()).tvFocus(12.dp)
            .cliqueComOpcoes(onClick = { onClick(item) }, onOpcoes = { onOpcoes(item) }),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(forma).border(0.5.dp, LabTheme.glassBorder, forma)) {
            // O "Primary" do episódio já é uma cena; o do filme é o cartaz em pé.
            val tipos = if (item.tipo == "Episode") listOf("Primary", "Thumb") else listOf("Thumb", "Backdrop", "Primary")
            ImagemDoServidor(servidor, item.id, tipos, 480, Modifier.fillMaxSize())
            Progresso(item.progresso)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.serie ?: item.nome, color = LabTheme.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val detalhe = when {
            item.tipo == "Episode" -> listOfNotNull(numeroDoEpisodio(item), item.nome).joinToString(" · ")
            item.posicao != null && item.duracao != null && item.duracao > item.posicao ->
                stringResource(R.string.faltam, TimeFormat.spoken(item.duracao - item.posicao))
            else -> item.ano?.toString().orEmpty()
        }
        Text(detalhe, color = LabTheme.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun numeroDoEpisodio(item: Item): String? {
    val temporada = item.temporada ?: return null
    val numero = item.numero ?: return null
    return stringResource(R.string.episodio_curto, temporada, numero)
}

/** "Recentes": o cartaz em pé, com os episódios por ver ou a marca de visto. */
@Composable
private fun Cartaz(servidor: ServidorDeMidia, item: Item, onClick: (Item) -> Unit, onOpcoes: (Item) -> Unit) {
    val forma = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(112.dp * Escala.fator()).tvFocus(10.dp)
            .cliqueComOpcoes(onClick = { onClick(item) }, onOpcoes = { onOpcoes(item) }),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(forma).border(0.5.dp, LabTheme.glassBorder, forma)) {
            // Episódio solto mostra o cartaz da série: a cena dele, cortada em
            // pé, não diria de onde ele é.
            val id = if (item.tipo == "Episode") item.serieId ?: item.id else item.id
            ImagemDoServidor(servidor, id, listOf("Primary"), 300, Modifier.fillMaxSize(), Icons.Filled.Tv)
            val marca = Modifier.align(Alignment.TopEnd).padding(6.dp)
            when {
                item.naoVistos != null -> Text(
                    item.naoVistos.toString(), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = marca.clip(CircleShape).background(LabTheme.accent).padding(horizontal = 7.dp, vertical = 2.dp),
                )
                item.visto -> Icon(
                    Icons.Filled.Check, null, tint = Color.Black,
                    modifier = marca.size(20.dp).clip(CircleShape).background(LabTheme.accent).padding(3.dp),
                )
            }
            Progresso(item.progresso)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (item.tipo == "Episode") item.serie ?: item.nome else item.nome,
            color = LabTheme.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        item.ano?.let { Text(it.toString(), color = LabTheme.faint, fontSize = 11.sp) }
    }
}

@Composable
private fun CartaoDeBiblioteca(servidor: ServidorDeMidia, biblioteca: Item, onClick: (Item) -> Unit, modifier: Modifier = Modifier) {
    val forma = RoundedCornerShape(12.dp)
    Column(modifier.width(176.dp * Escala.fator()).tvFocus(12.dp).clickable { onClick(biblioteca) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(forma).border(0.5.dp, LabTheme.glassBorder, forma)) {
            ImagemDoServidor(servidor, biblioteca.id, listOf("Primary"), 400, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(6.dp))
        Text(
            biblioteca.nome, color = LabTheme.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BoxScope.Progresso(progresso: Float?) {
    if (progresso == null) return
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
            .background(Color.White.copy(alpha = 0.25f))
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progresso.coerceAtLeast(0.03f)).background(LabTheme.accent))
    }
}

/**
 * Série, temporada ou coleção dentro de uma biblioteca: a capa deitada, como
 * no app do servidor e no do computador. Antes era uma linha com um ícone de
 * pasta, e uma biblioteca de séries virava uma lista de nomes sem rosto.
 *
 * O selo diz quantos episódios faltam ver; o visto, quando não falta nenhum.
 * Segurar abre o "Já reproduzido", que numa série vale para todos os episódios.
 */
@Composable
internal fun CapaDePastaDoServidor(
    servidor: ServidorDeMidia,
    item: Item,
    onClick: () -> Unit,
    onOpcoes: () -> Unit,
) {
    val forma = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().tvFocus(12.dp).cliqueComOpcoes(onClick, onOpcoes)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(forma).border(0.5.dp, LabTheme.glassBorder, forma)) {
            ImagemDoServidor(
                servidor, idDaCapa(item), listOf("Thumb", "Backdrop", "Primary"), 480, Modifier.fillMaxSize(),
                if (item.tipo == "Series" || item.tipo == "Season") Icons.Filled.Tv else Icons.Filled.Movie,
            )
            SeloDeVistos(item, Modifier.align(Alignment.BottomEnd).padding(7.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            item.nome, color = LabTheme.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(item.ano?.toString() ?: " ", color = LabTheme.muted, fontSize = 11.sp, maxLines = 1)
    }
}

/** A mesma capa, pequena, na linha da lista — para quem prefere a pasta em lista. */
@Composable
internal fun LinhaDePastaDoServidor(
    servidor: ServidorDeMidia,
    item: Item,
    onClick: () -> Unit,
    onOpcoes: () -> Unit,
) {
    val forma = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().tvFocus(LabTheme.radiusSmall, scale = 1.02f)
            .clip(RoundedCornerShape(LabTheme.radiusSmall))
            .cliqueComOpcoes(onClick, onOpcoes)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(124.dp * Escala.fator()).aspectRatio(16f / 9f).clip(forma).border(0.5.dp, LabTheme.glassBorder, forma)) {
            ImagemDoServidor(
                servidor, idDaCapa(item), listOf("Thumb", "Backdrop", "Primary"), 320, Modifier.fillMaxSize(),
                if (item.tipo == "Series" || item.tipo == "Season") Icons.Filled.Tv else Icons.Filled.Movie,
            )
            SeloDeVistos(item, Modifier.align(Alignment.BottomEnd).padding(5.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.nome, color = LabTheme.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            item.ano?.let { Text(it.toString(), color = LabTheme.muted, fontSize = 11.sp) }
        }
    }
}

/**
 * A temporada quase nunca tem cena deitada própria, só o cartaz em pé — que
 * cortado em 16:9 vira um retalho. A da série serve a todas as temporadas.
 */
private fun idDaCapa(item: Item): String =
    if (item.tipo == "Season") item.serieId ?: item.id else item.id

@Composable
private fun SeloDeVistos(item: Item, modifier: Modifier) {
    when {
        item.naoVistos != null -> Text(
            item.naoVistos.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
        item.visto -> Icon(
            Icons.Filled.Check, null, tint = Color.Black,
            modifier = modifier.size(20.dp).clip(CircleShape).background(LabTheme.accent).padding(3.dp),
        )
    }
}

/**
 * Cartazes e cenas que o servidor já tem prontos — diferente das miniaturas
 * das pastas, aqui não há quadro a tirar do vídeo, só a imagem a baixar.
 */
private object CapasDoServidor {
    val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    val falharam: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val limite = Semaphore(4)
}

@Composable
internal fun ImagemDoServidor(
    servidor: ServidorDeMidia,
    id: String,
    tipos: List<String>,
    largura: Int,
    modifier: Modifier,
    semImagem: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Movie,
) {
    val chave = "${servidor.contaId}/$id/${tipos.joinToString(",")}"
    val imagem by produceState(CapasDoServidor.cache.get(chave), chave) {
        if (value != null || chave in CapasDoServidor.falharam) return@produceState
        val baixada = CapasDoServidor.limite.withPermit { servidor.imagem(id, tipos, largura) }
        if (baixada != null) CapasDoServidor.cache.put(chave, baixada) else CapasDoServidor.falharam += chave
        value = baixada
    }
    Box(
        modifier.background(Brush.linearGradient(listOf(Color(0xFF232228), Color(0xFF16161A)))),
        contentAlignment = Alignment.Center,
    ) {
        val pronta = imagem
        if (pronta != null) {
            Image(pronta.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(semImagem, null, tint = LabTheme.faint, modifier = Modifier.size(22.dp))
        }
    }
}
