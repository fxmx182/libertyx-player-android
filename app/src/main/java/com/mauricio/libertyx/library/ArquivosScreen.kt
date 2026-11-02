package com.mauricio.libertyx.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.labCard
import com.mauricio.libertyx.rede.AtalhoDeServidor
import com.mauricio.libertyx.tv.tvFocus

/**
 * A aba Arquivos: navegar por pasta, como num gerenciador de arquivos.
 *
 * O Início mostra as pastas como capas, para escolher pelo que se vê. Aqui é
 * o caminho de quem sabe onde o vídeo está: uma lista enxuta das pastas do
 * aparelho e, abaixo, cada servidor salvo.
 */
@Composable
fun ArquivosScreen(onAtalho: (AtalhoDeServidor) -> Unit, onAdicionarServidor: () -> Unit) {
    val contexto = LocalContext.current
    val opcoes = remember { LibraryOptions(contexto) }
    val volta = rememberVoltaAoApp()
    var grupos by remember { mutableStateOf(MediaLibrary.ultima ?: emptyList()) }
    LaunchedEffect(Unit) { grupos = MediaLibrary.scan(contexto) }
    val atalhos = remember(volta) { AtalhoDeServidor.todos(contexto) }

    var caminhoAberto by rememberSaveable { mutableStateOf<String?>(null) }
    var mostrandoOpcoes by remember { mutableStateOf(false) }
    val aberta = grupos.firstOrNull { it.path == caminhoAberto }
    BackHandler(enabled = aberta != null) { caminhoAberto = null }

    Box(Modifier.fillMaxSize()) {
        FundoDeCapa(aberta?.let(::capaDa))
        if (aberta != null) {
            Pasta(
                grupo = aberta, opcoes = opcoes, volta = volta, raiz = false,
                continuar = emptyList(), atalhos = emptyList(),
                onAtalho = {}, onRemoverDoHistorico = {},
                onVoltar = { caminhoAberto = null },
                onOpcoes = { mostrandoOpcoes = true },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = margens()) {
                item { BarraDoTopo(onVoltar = null, onOpcoes = null) }
                item {
                    Titulo(
                        stringResource(R.string.aba_arquivos),
                        pluralStringResource(R.plurals.n_pastas, grupos.size, grupos.size) + "  ·  " +
                            pluralStringResource(R.plurals.n_servidores, atalhos.size, atalhos.size),
                    )
                    Spacer(Modifier.size(18.dp))
                }
                if (grupos.isNotEmpty()) {
                    item { Secao(stringResource(R.string.neste_aparelho), grupos.size.toString()) }
                    items(grupos, key = { it.path }) { grupo ->
                        LinhaDeLugar(Icons.Filled.Folder, grupo.name, resumoDa(grupo)) { caminhoAberto = grupo.path }
                    }
                    item { Spacer(Modifier.size(14.dp)) }
                }
                item { Secao(stringResource(R.string.servidores), atalhos.size.takeIf { it > 0 }?.toString()) }
                items(atalhos, key = { it.chave }) { atalho ->
                    LinhaDeLugar(atalho.icone, atalho.nome, atalho.tipo) { onAtalho(atalho) }
                }
                // Cadastrar mora nos Ajustes; aqui fica só o atalho para lá,
                // para a lista vazia não ser um beco sem saída.
                item {
                    LinhaDeLugar(
                        Icons.Filled.Add, stringResource(R.string.adicionar_servidor),
                        stringResource(R.string.servidores_smb_sub), seta = false, onClick = onAdicionarServidor,
                    )
                }
            }
        }
    }

    if (mostrandoOpcoes) {
        FolhaDeOpcoes(onFechar = { mostrandoOpcoes = false; opcoes.save() }) { OpcoesDaBiblioteca(opcoes) }
    }
}

/** Uma pasta ou um servidor: o ícone numa caixinha, o nome e o que tem lá. */
@Composable
internal fun LinhaDeLugar(
    icone: ImageVector,
    titulo: String,
    subtitulo: String?,
    seta: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .tvFocus(LabTheme.radiusCard, scale = 1.02f)
            .clip(RoundedCornerShape(LabTheme.radiusCard)).labCard()
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                .background(LabTheme.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icone, null, tint = LabTheme.accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                titulo, color = LabTheme.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!subtitulo.isNullOrEmpty()) {
                Text(subtitulo, color = LabTheme.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (seta) Icon(Icons.Filled.ChevronRight, null, tint = LabTheme.faint)
    }
}
