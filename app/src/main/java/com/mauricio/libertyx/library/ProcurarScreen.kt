package com.mauricio.libertyx.library

import com.mauricio.libertyx.tv.setasTrocamDeCampo
import com.mauricio.libertyx.tv.tvFocus
import com.mauricio.libertyx.core.Device
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.player.Playback
import com.mauricio.libertyx.rede.ContaDeRede
import com.mauricio.libertyx.rede.ContasDeRede
import com.mauricio.libertyx.rede.ServidorDeMidia
import kotlinx.coroutines.delay
import java.text.Normalizer

/**
 * A aba Procurar: pelo nome, nos vídeos do aparelho e nos Jellyfin e Emby.
 *
 * SMB e WebDAV ficam de fora: não têm busca, e procurar neles seria descer
 * pasta por pasta do servidor inteiro a cada letra digitada.
 */
@Composable
fun ProcurarScreen() {
    val contexto = LocalContext.current
    val foco = LocalFocusManager.current
    val naTv = remember { Device.isTv(contexto) }
    var termo by rememberSaveable { mutableStateOf("") }
    var grupos by remember { mutableStateOf(MediaLibrary.ultima ?: emptyList()) }
    LaunchedEffect(Unit) { if (MediaLibrary.ultima == null) grupos = MediaLibrary.scan(contexto) }

    val limpo = termo.trim()
    val locais = remember(limpo, grupos) {
        if (limpo.isEmpty()) emptyList()
        else {
            val procura = semAcento(limpo)
            grupos.flatMap { it.items }.filter { semAcento(it.title).contains(procura) }.take(100)
        }
    }

    // Uma lista por servidor, na ordem em que foram salvos.
    var remotos by remember { mutableStateOf<List<Pair<ContaDeRede, List<ServidorDeMidia.Item>>>>(emptyList()) }
    // Do servidor, cada achado abre a página dele — o filme com a sinopse e o
    // "Assistir", a série com as temporadas —, por cima da busca.
    var abrindo by remember { mutableStateOf<Pair<ContaDeRede, ServidorDeMidia.Item>?>(null) }
    var buscando by remember { mutableStateOf(false) }
    LaunchedEffect(limpo) {
        remotos = emptyList()
        if (limpo.length < 2) { buscando = false; return@LaunchedEffect }
        // Espera a pessoa parar de digitar: uma consulta por letra só
        // ocuparia o servidor com buscas que ninguém vai ler.
        delay(450)
        val loja = ContasDeRede.get(contexto)
        val contas = loja.contas.filter { it.tipo != ContaDeRede.Tipo.WEBDAV }
        if (contas.isEmpty()) return@LaunchedEffect
        buscando = true
        remotos = contas.mapNotNull { conta ->
            val achados = runCatching { ServidorDeMidia(conta, loja.segredo(conta)).buscar(limpo) }
                .getOrDefault(emptyList())
            achados.takeIf { lista -> lista.isNotEmpty() }?.let { conta to it }
        }
        buscando = false
    }

    abrindo?.let { (conta, item) ->
        com.mauricio.libertyx.rede.ContaScreen(conta, onBack = { abrindo = null }, abrir = item)
        return
    }

    Box(Modifier.fillMaxSize()) {
        FundoDeCapa(null)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = margens()) {
            item { BarraDoTopo(onVoltar = null, onOpcoes = null) }
            item { Titulo(stringResource(R.string.aba_procurar), stringResource(R.string.procurar_explica)) }
            item {
                Spacer(Modifier.height(16.dp))
                // Na TV, soltar o foco o deixaria em lugar nenhum: ele desce
                // para o primeiro resultado.
                CampoDeBusca(termo, { termo = it }, onPronto = {
                    if (naTv) foco.moveFocus(FocusDirection.Down) else foco.clearFocus()
                })
                Spacer(Modifier.height(18.dp))
            }

            if (locais.isNotEmpty()) {
                item { Secao(stringResource(R.string.neste_aparelho), locais.size.toString()) }
                items(locais, key = { "l" + it.id }) { item ->
                    LinhaDeVideo(item) { Playback.start(contexto, item, locais) }
                }
                item { Spacer(Modifier.height(14.dp)) }
            }
            remotos.forEach { (conta, achados) ->
                item(key = "s" + conta.id) { Secao(conta.nome, achados.size.toString()) }
                items(achados, key = { "r" + it.id }) { item ->
                    val origem = MediaOrigin.Midia(conta.id, item.id)
                    val video = MediaItem(
                        id = origem.resumeKey,
                        // Episódio solto não diz nada sem a série na frente.
                        title = item.serie?.let { serie -> "$serie · ${item.titulo}" } ?: item.titulo,
                        origin = origem, modifiedAt = item.criado, duration = item.duracao,
                    )
                    LinhaDeVideo(video) { abrindo = conta to item }
                }
                item(key = "e" + conta.id) { Spacer(Modifier.height(14.dp)) }
            }

            item {
                when {
                    buscando -> Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = LabTheme.accent)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.procurando_nos_servidores), color = LabTheme.muted, fontSize = 13.sp)
                    }
                    limpo.isNotEmpty() && locais.isEmpty() && remotos.isEmpty() ->
                        Text(stringResource(R.string.nada_encontrado, limpo), color = LabTheme.muted, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun CampoDeBusca(texto: String, onTexto: (String) -> Unit, onPronto: () -> Unit) {
    val forma = RoundedCornerShape(16.dp)
    Row(
        // As setas saem do campo para a lista e para as abas: sem isto, o
        // controle remoto entra no campo e não sai mais.
        Modifier.fillMaxWidth().height(52.dp).tvFocus(16.dp, scale = 1.02f).setasTrocamDeCampo()
            .background(LabTheme.glass, forma)
            .border(0.5.dp, LabTheme.glassBorder, forma).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = LabTheme.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (texto.isEmpty()) Text(stringResource(R.string.procurar_dica), color = LabTheme.faint, fontSize = 16.sp)
            BasicTextField(
                value = texto, onValueChange = onTexto, singleLine = true,
                textStyle = TextStyle(color = LabTheme.text, fontSize = 16.sp),
                cursorBrush = SolidColor(LabTheme.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onPronto() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (texto.isNotEmpty()) {
            IconButton(onClick = { onTexto("") }) {
                Icon(Icons.Filled.Close, stringResource(R.string.limpar), tint = LabTheme.muted)
            }
        }
    }
}

/** "Ação" acha "acao": ninguém digita acento no teclado do celular para procurar. */
private fun semAcento(texto: String): String =
    Normalizer.normalize(texto, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
