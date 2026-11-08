package com.mauricio.libertyx.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.BuildConfig
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.PlayerPreferences
import com.mauricio.libertyx.core.Pro
import com.mauricio.libertyx.core.labCard
import com.mauricio.libertyx.pro.ProActivity
import com.mauricio.libertyx.tv.tvFocus

/** Onde o LibertyX mora na internet — o link que se compartilha. */
private const val SITE = "https://fxmx182.github.io"
private const val EMAIL = "libertyx.suporte@gmail.com"

/**
 * A aba Ajustes: tudo o que ficava espalhado em botões no alto da tela,
 * numa lista só, no espírito da do Infuse.
 *
 * Cada linha leva a um lugar; nada se muda aqui mesmo. Uma lista que só
 * aponta é a que se lê de relance.
 */
@Composable
fun AjustesScreen(onServidores: () -> Unit, onWebDav: () -> Unit, onGuia: (() -> Unit)?) {
    val contexto = LocalContext.current
    var folha by remember { mutableStateOf<Folha?>(null) }
    val estadoPro by Pro.estado.collectAsState()
    val valorPro = when (val e = estadoPro) {
        is Pro.Estado.Comprado -> stringResource(R.string.pro_ativo)
        is Pro.Estado.Teste -> pluralStringResource(R.plurals.pro_teste_restam, e.diasRestantes, e.diasRestantes)
        Pro.Estado.Expirado -> null
    }

    Box(Modifier.fillMaxSize()) {
        FundoDeCapa(null)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = margens()) {
            item { BarraDoTopo(onVoltar = null, onOpcoes = null) }
            item {
                Titulo(stringResource(R.string.aba_ajustes), "LibertyX Player ${BuildConfig.VERSION_NAME}")
                Spacer(Modifier.height(18.dp))
            }

            item {
                Grupo(stringResource(R.string.biblioteca)) {
                    Linha(Icons.Outlined.Dns, stringResource(R.string.servidores), stringResource(R.string.servidores_sub), onClick = onServidores)
                    Linha(Icons.Outlined.Cloud, stringResource(R.string.webdav_nextcloud), stringResource(R.string.webdav_sub), ultima = true, onClick = onWebDav)
                }
            }
            item {
                SecaoDaDock()

                Grupo(stringResource(R.string.preferencias)) {
                    Linha(Icons.Outlined.Tune, stringResource(R.string.exibicao), stringResource(R.string.exibicao_sub)) {
                        folha = Folha.EXIBICAO
                    }
                    Linha(Icons.Outlined.PlayCircle, stringResource(R.string.reproducao), stringResource(R.string.reproducao_sub), ultima = true) {
                        folha = Folha.REPRODUCAO
                    }
                }
            }
            item {
                Grupo("LibertyX") {
                    Linha(Icons.Outlined.WorkspacePremium, stringResource(R.string.pro_menu), valorPro) {
                        ProActivity.abrir(contexto)
                    }
                    if (onGuia != null) Linha(Icons.Outlined.HelpOutline, stringResource(R.string.ver_guia), onClick = onGuia)
                    Linha(Icons.Outlined.Description, stringResource(R.string.licencas), ultima = true) {
                        ProActivity.abrir(contexto, licencas = true)
                    }
                }
            }
            item {
                Grupo(stringResource(R.string.fale_com_a_gente)) {
                    Linha(Icons.Outlined.Share, stringResource(R.string.compartilhar_app), externo = true) {
                        compartilhar(contexto)
                    }
                    Linha(
                        painter = R.drawable.ic_x, titulo = stringResource(R.string.seguir_no_x),
                        valor = stringResource(R.string.em_breve), externo = true,
                    ) {
                        Toast.makeText(contexto, R.string.x_em_breve, Toast.LENGTH_SHORT).show()
                    }
                    Linha(Icons.Outlined.Email, stringResource(R.string.enviar_email), EMAIL, externo = true, ultima = true) {
                        enviarEmail(contexto)
                    }
                }
            }
            item {
                // Qual build está instalado, à mão: "a mudança não funcionou"
                // e "instalei o APK antigo" são a mesma tela sem isto.
                Text(
                    "LibertyX Player ${BuildConfig.VERSION_NAME} · ${BuildConfig.COMMIT}",
                    color = LabTheme.faint, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }

    when (folha) {
        Folha.EXIBICAO -> {
            val opcoes = remember { LibraryOptions(contexto) }
            FolhaDeOpcoes(onFechar = { folha = null; opcoes.save() }) { OpcoesDaBiblioteca(opcoes) }
        }
        Folha.REPRODUCAO -> FolhaDeOpcoes(onFechar = { folha = null }) { OpcoesDeReproducao() }
        null -> Unit
    }
}

private enum class Folha { EXIBICAO, REPRODUCAO }

/** Um bloco de linhas com o título em cima, como os grupos dos Ajustes do iPhone. */
@Composable
private fun Grupo(titulo: String, linhas: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Secao(titulo)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(LabTheme.radiusCard)).labCard()) { linhas() }
    }
}

@Composable
private fun Linha(
    icone: ImageVector? = null,
    titulo: String,
    sub: String? = null,
    valor: String? = null,
    externo: Boolean = false,
    ultima: Boolean = false,
    painter: Int? = null,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().tvFocus(LabTheme.radiusSmall, scale = 1.01f)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                when {
                    painter != null -> Icon(painterResource(painter), null, tint = LabTheme.accent, modifier = Modifier.size(20.dp))
                    icone != null -> Icon(icone, null, tint = LabTheme.accent, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(titulo, color = LabTheme.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (sub != null) {
                    Text(sub, color = LabTheme.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (valor != null) {
                Text(valor, color = LabTheme.muted, fontSize = 13.sp, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                if (externo) Icons.AutoMirrored.Filled.OpenInNew else Icons.Filled.ChevronRight, null,
                tint = LabTheme.faint, modifier = Modifier.size(if (externo) 18.dp else 24.dp),
            )
        }
        if (!ultima) HorizontalDivider(Modifier.padding(start = 58.dp), thickness = 0.5.dp, color = LabTheme.glassBorder)
    }
}

/** As preferências do player, que antes só se mudavam com um vídeo aberto. */
@Composable
private fun OpcoesDeReproducao() {
    val contexto = LocalContext.current
    val prefs = remember { PlayerPreferences(contexto) }
    var ocultar by remember { mutableStateOf(prefs.autoHide) }
    var velocidade by remember { mutableStateOf(prefs.holdSpeed) }
    var exata by remember { mutableStateOf(prefs.preciseScrub) }
    var perguntar by remember { mutableStateOf(prefs.askToResume) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 22.dp).padding(top = 4.dp, bottom = 20.dp),
    ) {
        Text(stringResource(R.string.reproducao), color = LabTheme.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(18.dp))

        Secao(stringResource(R.string.ocultar_barra_depois))
        // Duas por linha: "10 segundos" com o visto na frente não cabe em quatro.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlayerPreferences.AutoHide.entries.chunked(2).forEach { linha ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    linha.forEach { opcao ->
                        Pilula(opcao.title, ocultar == opcao, Modifier.weight(1f)) {
                            ocultar = opcao; prefs.autoHide = opcao
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Secao(stringResource(R.string.segurar_para_acelerar))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerPreferences.holdSpeedOptions.forEach { opcao ->
                Pilula(velocidadeEmTexto(opcao), velocidade == opcao, Modifier.weight(1f)) {
                    velocidade = opcao; prefs.holdSpeed = opcao
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Secao(stringResource(R.string.rolagem))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pilula(stringResource(R.string.t_quadro_a_quadro), exata, Modifier.weight(1f)) { exata = true; prefs.preciseScrub = true }
            Pilula(stringResource(R.string.por_keyframe), !exata, Modifier.weight(1f)) { exata = false; prefs.preciseScrub = false }
        }

        Spacer(Modifier.height(20.dp))
        Secao(stringResource(R.string.ao_reabrir))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pilula(stringResource(R.string.perguntar), perguntar, Modifier.weight(1f)) { perguntar = true; prefs.askToResume = true }
            Pilula(stringResource(R.string.retomar_direto), !perguntar, Modifier.weight(1f)) { perguntar = false; prefs.askToResume = false }
        }
    }
}

private fun velocidadeEmTexto(valor: Float): String =
    if (valor % 1f == 0f) "${valor.toInt()}×"
    else String.format(java.util.Locale.getDefault(), "%.1f×", valor)

private fun compartilhar(contexto: Context) {
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, contexto.getString(R.string.compartilhar_texto, SITE))
    }
    // Muita TV não tem app nenhum para receber o texto; lá o link aparece
    // na tela, para a pessoa anotar.
    try {
        contexto.startActivity(Intent.createChooser(envio, contexto.getString(R.string.compartilhar_app)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(contexto, SITE, Toast.LENGTH_LONG).show()
    }
}

private fun enviarEmail(contexto: Context) {
    // `mailto:` com SENDTO: só apps de email respondem, e não o WhatsApp.
    val email = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "LibertyX Player ${BuildConfig.VERSION_NAME} (Android)")
    }
    try {
        contexto.startActivity(email)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(contexto, contexto.getString(R.string.sem_app_de_email, EMAIL), Toast.LENGTH_LONG).show()
    }
}

/**
 * Ajustes › Dock: o que fica a um toque além de Início e Ajustes. Lotada (três
 * escolhidas), as outras chaves apagam até abrir vaga.
 */
@Composable
private fun SecaoDaDock() {
    val contexto = LocalContext.current
    val versao = DockEscolhida.versao.intValue
    val escolhidas = remember(versao) { DockEscolhida.validos(contexto) }
    val contas = remember(versao) { com.mauricio.libertyx.rede.ContasDeRede.get(contexto).contas }
    val smb = remember(versao) { com.mauricio.libertyx.smb.SmbServerStore.get(contexto).servers }
    val cheia = escolhidas.size >= DockEscolhida.MAXIMO
    val opcoes = buildList {
        add(Triple<Aba, String, Pair<ImageVector?, Int?>>(Aba.Arquivos, stringResource(R.string.aba_arquivos), Icons.Outlined.Folder to null))
        add(Triple(Aba.Procurar, stringResource(R.string.aba_procurar), Icons.Outlined.Search to null))
        smb.forEach { add(Triple(Aba.Smb(it.id), it.name, null to R.drawable.logo_smb)) }
        contas.forEach {
            val desenho: Pair<ImageVector?, Int?> =
                if (it.tipo == com.mauricio.libertyx.rede.ContaDeRede.Tipo.JELLYFIN) null to R.drawable.logo_jellyfin
                else Icons.Outlined.Cloud to null
            add(Triple(Aba.Conta(it.id), it.nome, desenho))
        }
    }
    Column(Modifier.padding(bottom = 18.dp)) {
        Secao(stringResource(R.string.dock_titulo))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(LabTheme.radiusCard)).labCard()) {
            opcoes.forEachIndexed { i, (aba, nome, desenho) ->
                val ligada = aba in escolhidas
                val bloqueada = cheia && !ligada
                Column {
                    Row(
                        Modifier.fillMaxWidth().tvFocus(LabTheme.radiusSmall, scale = 1.01f)
                            .clickable(enabled = !bloqueada) { DockEscolhida.alternar(contexto, aba) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .alpha(if (bloqueada) 0.45f else 1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                            val (vetor, imagem) = desenho
                            if (imagem != null) androidx.compose.foundation.Image(painterResource(imagem), null, Modifier.size(22.dp))
                            else if (vetor != null) Icon(vetor, null, tint = LabTheme.accent, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(nome, color = LabTheme.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = ligada, onCheckedChange = null, enabled = !bloqueada,
                            colors = SwitchDefaults.colors(checkedTrackColor = LabTheme.accent, checkedThumbColor = Color.Black),
                        )
                    }
                    if (i < opcoes.lastIndex) HorizontalDivider(Modifier.padding(start = 58.dp), thickness = 0.5.dp, color = LabTheme.glassBorder)
                }
            }
        }
        val naTv = com.mauricio.libertyx.core.Device.isTv(contexto)
        Text(
            stringResource(if (naTv) R.string.dock_explica_tv else R.string.dock_explica),
            color = LabTheme.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}
