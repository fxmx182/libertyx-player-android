package com.mauricio.libertyx.pro

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.BuildConfig
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.AtivacaoNaRede
import com.mauricio.libertyx.core.Chave
import com.mauricio.libertyx.core.Device
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.LibertyXTheme
import com.mauricio.libertyx.core.Loja
import com.mauricio.libertyx.core.Pro
import com.mauricio.libertyx.core.labCard
import com.mauricio.libertyx.tv.tvFocus

/**
 * A tela do LibertyX Pro — e, atrás dela, a das licenças.
 *
 * Uma atividade própria, e não um destino da navegação da biblioteca, porque
 * ela é aberta de dois mundos: da biblioteca (Compose) e do player (Views),
 * quando alguém toca numa ferramenta Pro depois do teste.
 */
class ProActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val abrirLicencas = intent.getBooleanExtra(EXTRA_LICENCAS, false)
        val pedido = intent.getStringExtra(EXTRA_RECURSO)?.let { runCatching { Pro.Recurso.valueOf(it) }.getOrNull() }
        setContent {
            LibertyXTheme {
                Surface(color = LabTheme.background, modifier = Modifier.fillMaxSize()) {
                    var licencas by rememberSaveable { mutableStateOf(abrirLicencas) }
                    var chave by rememberSaveable { mutableStateOf(false) }
                    BackHandler(enabled = licencas && !abrirLicencas) { licencas = false }
                    BackHandler(enabled = chave) { chave = false }
                    val tela = when { licencas -> 1; chave -> 2; else -> 0 }
                    AnimatedContent(tela, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pro") { qual ->
                        when (qual) {
                            1 -> TelaDeLicencas(onVoltar = { if (abrirLicencas) finish() else licencas = false })
                            2 -> TelaDaChave(onVoltar = { chave = false })
                            else -> TelaDoPro(pedido, onFechar = ::finish, onLicencas = { licencas = true }, onChave = { chave = true })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // O dia pode ter virado com a tela aberta, e a compra pode ter
        // compensado (boleto) enquanto o app estava fechado.
        Pro.recalcular()
        Loja.atualizar()
    }

    companion object {
        private const val EXTRA_LICENCAS = "licencas"
        private const val EXTRA_RECURSO = "recurso"

        fun abrir(contexto: Context, licencas: Boolean = false, recurso: Pro.Recurso? = null) {
            contexto.startActivity(
                Intent(contexto, ProActivity::class.java).putExtra(EXTRA_LICENCAS, licencas)
                    .putExtra(EXTRA_RECURSO, recurso?.name)
                    .apply { if (contexto !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }

        /**
         * A porta de cada recurso Pro: `true` quando está liberado; senão abre
         * esta tela com o recurso pedido em destaque e devolve `false`.
         */
        fun exigir(contexto: Context, recurso: Pro.Recurso): Boolean {
            if (Pro.ativo) return true
            abrir(contexto, recurso = recurso)
            return false
        }
    }
}

private data class Beneficio(
    val recurso: Pro.Recurso,
    @DrawableRes val icone: Int,
    @StringRes val titulo: Int,
    @StringRes val texto: Int,
)

/** Os cinco recursos, na ordem de [Pro.Recurso]. Na TV, só os que existem lá. */
private fun beneficios(naTv: Boolean): List<Beneficio> = Pro.Recurso.entries.mapNotNull { recurso ->
    when (recurso) {
        Pro.Recurso.REDE -> Beneficio(recurso, R.drawable.ic_server, R.string.pro_rede, R.string.pro_rede_texto)
        Pro.Recurso.SEGUNDO_PLANO ->
            if (naTv) null else Beneficio(recurso, R.drawable.ic_audio, R.string.pro_segundo_plano, R.string.pro_segundo_plano_texto)
        Pro.Recurso.JANELA ->
            if (naTv) null else Beneficio(recurso, R.drawable.ic_pip, R.string.pro_janela, R.string.pro_janela_texto)
        Pro.Recurso.DORMIR -> Beneficio(recurso, R.drawable.ic_timer, R.string.pro_dormir, R.string.pro_dormir_texto)
        Pro.Recurso.CAPTURA -> Beneficio(recurso, R.drawable.ic_camera, R.string.pro_captura, R.string.pro_captura_texto)
    }
}

@Composable
private fun TelaDoPro(pedido: Pro.Recurso?, onFechar: () -> Unit, onLicencas: () -> Unit, onChave: () -> Unit) {
    val contexto = LocalContext.current
    val naTv = remember { Device.isTv(contexto) }
    val estado by Pro.estado.collectAsState()
    val situacao by Loja.situacao.collectAsState()
    val pendente by Loja.pendente.collectAsState()

    Box(Modifier.fillMaxSize()) {
        // O brilho dourado atrás da marca: a única cor forte da tela, no
        // lugar de onde o olho começa.
        // Centrado na marca e com raio menor que a caixa: o degradê some
        // antes da borda, sem deixar uma linha reta atrás do cartão.
        Box(
            Modifier.fillMaxWidth().height(480.dp).drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(LabTheme.accent.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width / 2, size.height * 0.36f),
                        radius = size.height * 0.62f,
                    )
                )
            }
        )
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                BotaoIcone(Icons.Rounded.Close, stringResource(R.string.fechar), onFechar)
            }
            Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painterResource(R.drawable.ic_marca), null,
                    Modifier.size(84.dp),
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LibertyX", style = LabTheme.headline)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "PRO", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.background(LabTheme.accent, RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Situacao(estado, pendente)
                Spacer(Modifier.height(22.dp))

                Column(Modifier.fillMaxWidth().labCard().padding(vertical = 6.dp)) {
                    // O recurso que trouxe a pessoa até aqui fica aceso: ela tocou
                    // na janela flutuante, e é a janela que ela procura na lista.
                    beneficios(naTv).forEach { LinhaDeBeneficio(it, destaque = it.recurso == pedido && estado is Pro.Estado.Expirado) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.pro_gratis_para_sempre),
                    color = LabTheme.muted, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))

                if (estado !is Pro.Estado.Comprado) {
                    // No APK do GitHub num aparelho sem Play (Fire TV), o botão da Play não leva a
                    // lugar nenhum: some, e o Telegram vira o botão principal.
                    val comPlay = !BuildConfig.CHAVE_LIBERTYX || situacao !is Loja.Situacao.Indisponivel
                    if (comPlay) {
                        BotaoDeCompra(situacao, pendente)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (BuildConfig.CHAVE_LIBERTYX) {
                        BotaoTelegram(principal = !comPlay, naTv = naTv)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (comPlay) {
                        Text(
                            stringResource(R.string.pro_uma_vez),
                            color = LabTheme.faint, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        LinkDeTexto(stringResource(R.string.pro_restaurar)) {
                            Loja.atualizar()
                            Toast.makeText(contexto, R.string.pro_restaurando, Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (BuildConfig.CHAVE_LIBERTYX) LinkDeTexto(stringResource(R.string.pro_tenho_chave), onChave)
                } else if (BuildConfig.CHAVE_LIBERTYX) {
                    Pro.licencaDaChave?.let {
                        Text(stringResource(R.string.chave_licenca, it), color = LabTheme.faint, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                LinkDeTexto(stringResource(R.string.licencas), onLicencas)
            }
        }
    }
}

@Composable
private fun Situacao(estado: Pro.Estado, pendente: Boolean) {
    val (texto, cor) = when {
        estado is Pro.Estado.Comprado -> stringResource(R.string.pro_ativo) to LabTheme.green
        pendente -> stringResource(R.string.pro_pendente) to LabTheme.orange
        estado is Pro.Estado.Teste ->
            pluralStringResource(R.plurals.pro_teste_restam, estado.diasRestantes, estado.diasRestantes) to LabTheme.accent
        else -> stringResource(R.string.pro_teste_acabou) to LabTheme.muted
    }
    Row(
        Modifier.background(cor.copy(alpha = 0.13f), CircleShape)
            .border(0.5.dp, cor.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (estado is Pro.Estado.Comprado) {
            Icon(Icons.Rounded.CheckCircle, null, tint = cor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(texto, color = cor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LinhaDeBeneficio(b: Beneficio, destaque: Boolean) {
    val forma = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp)
            .then(
                if (destaque) Modifier.background(LabTheme.accent.copy(alpha = 0.10f), forma)
                    .border(1.dp, LabTheme.accent.copy(alpha = 0.6f), forma)
                else Modifier
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(LabTheme.accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(b.icone), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(LabTheme.accent))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(b.titulo), color = LabTheme.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(b.texto), color = LabTheme.muted, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun BotaoDeCompra(situacao: Loja.Situacao, pendente: Boolean) {
    val contexto = LocalContext.current
    val atividade = contexto as? Activity
    val habilitado = situacao !is Loja.Situacao.Conectando && !pendente
    val rotulo = when (situacao) {
        is Loja.Situacao.Pronta -> stringResource(R.string.pro_comprar_por, situacao.preco)
        Loja.Situacao.Conectando -> stringResource(R.string.pro_carregando)
        // Aparelho sem a loja, ou APK de fora dela: a compra é feita lá.
        Loja.Situacao.Indisponivel -> stringResource(R.string.pro_na_play)
    }
    val forma = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().tvFocus(16.dp, scale = 1.03f).clip(forma)
            .background(if (habilitado) LabTheme.accent else LabTheme.accent.copy(alpha = 0.35f), forma)
            .clickable(enabled = habilitado) {
                val comprou = situacao is Loja.Situacao.Pronta && atividade != null && Loja.comprar(atividade)
                if (!comprou) abrirNaLoja(contexto)
            }
            .padding(vertical = 17.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(rotulo, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** O bot da loja LibertyX, aberto direto na tela do Player. */
private const val BOT_TELEGRAM = "https://t.me/libertx_partition_bot?start=player"
private const val BOT_TELEGRAM_CURTO = "t.me/libertx_partition_bot"

private fun abrirTelegram(contexto: Context) {
    try {
        contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BOT_TELEGRAM)))
    } catch (_: ActivityNotFoundException) {
        // TV sem navegador nem Telegram: a compra é pelo celular.
        Toast.makeText(contexto, contexto.getString(R.string.pro_telegram_tv, BOT_TELEGRAM_CURTO), Toast.LENGTH_LONG).show()
    }
}

/**
 * Comprar com Bitcoin ou USDT pelo bot do Telegram, que manda a chave na hora. Na TV quase
 * nunca há Telegram: o botão ainda tenta, e a linha de baixo diz o endereço para o celular.
 */
@Composable
private fun BotaoTelegram(principal: Boolean, naTv: Boolean) {
    val contexto = LocalContext.current
    val forma = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().tvFocus(16.dp, scale = 1.03f).clip(forma)
            .then(
                if (principal) Modifier.background(LabTheme.accent, forma)
                else Modifier.background(LabTheme.glass, forma).border(1.dp, LabTheme.accent.copy(alpha = 0.55f), forma)
            )
            .clickable { abrirTelegram(contexto) }
            .padding(vertical = 13.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.pro_telegram),
            color = if (principal) Color.Black else LabTheme.accent, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (naTv) stringResource(R.string.pro_telegram_tv, BOT_TELEGRAM_CURTO) else stringResource(R.string.pro_telegram_sub),
            color = if (principal) Color.Black.copy(alpha = 0.7f) else LabTheme.muted,
            fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
        )
    }
}

private fun mensagemDoErro(contexto: Context, erro: Chave.Erro): String = contexto.getString(
    when (erro) {
        Chave.Erro.FORMATO -> R.string.chave_erro_formato
        Chave.Erro.INVALIDA -> R.string.chave_erro_invalida
        Chave.Erro.OUTRO_APP -> R.string.chave_erro_outro_app
        Chave.Erro.VERSAO -> R.string.chave_erro_versao
    }
)

/**
 * Colar a chave do Telegram. No celular é um campo e o botão de colar; na TV o mesmo campo
 * existe, mas o caminho de verdade é o celular: enquanto esta tela está aberta, a TV responde
 * num endereço da rede de casa ([AtivacaoNaRede]) onde a chave é colada pelo navegador.
 */
@Composable
private fun TelaDaChave(onVoltar: () -> Unit) {
    val contexto = LocalContext.current
    val naTv = remember { Device.isTv(contexto) }
    val estado by Pro.estado.collectAsState()
    val ativo = estado is Pro.Estado.Comprado
    var texto by rememberSaveable { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var endereco by remember { mutableStateOf<String?>(null) }
    val area = LocalClipboardManager.current

    if (naTv && !ativo) {
        DisposableEffect(Unit) {
            val servidor = AtivacaoNaRede(
                AtivacaoNaRede.Textos(
                    titulo = contexto.getString(R.string.chave_web_titulo),
                    explicacao = contexto.getString(R.string.chave_web_texto),
                    botao = contexto.getString(R.string.chave_web_botao),
                    ok = contexto.getString(R.string.chave_web_ok),
                    erro = { mensagemDoErro(contexto, it) },
                ),
                aoReceber = { Pro.ativarChave(it) },
            )
            endereco = servidor.iniciar()
            onDispose { servidor.parar() }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BotaoIcone(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.voltar), onVoltar)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.chave_titulo), color = LabTheme.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp)) {
                if (ativo) {
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = LabTheme.green, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.chave_ok), color = LabTheme.green, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    Pro.licencaDaChave?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.chave_licenca, it), color = LabTheme.faint, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                    BotaoAmarelo(stringResource(R.string.voltar), onVoltar)
                    return@Column
                }
                if (naTv) {
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().labCard().padding(18.dp)) {
                        Text(stringResource(R.string.chave_tv_titulo), color = LabTheme.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        val e = endereco
                        if (e != null) {
                            Text(stringResource(R.string.chave_tv_texto), color = LabTheme.muted, fontSize = 14.sp, lineHeight = 19.sp)
                            Spacer(Modifier.height(10.dp))
                            Text(e, color = LabTheme.text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                        } else {
                            Text(stringResource(R.string.chave_tv_sem_rede), color = LabTheme.muted, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
                Text(stringResource(R.string.chave_texto), color = LabTheme.muted, fontSize = 14.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = texto, onValueChange = { texto = it; erro = null },
                    label = { Text(stringResource(R.string.chave_campo)) },
                    placeholder = { Text("LXP1-…") },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                erro?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = LabTheme.red, fontSize = 13.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!naTv) {
                        Box(Modifier.weight(1f)) {
                            BotaoVidro(stringResource(R.string.chave_colar)) {
                                area.getText()?.text?.let { texto = it.trim(); erro = null }
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        BotaoAmarelo(stringResource(R.string.chave_ativar)) {
                            when (val r = Pro.ativarChave(texto)) {
                                is Chave.Resultado.Valida -> erro = null
                                is Chave.Resultado.Recusada -> erro = mensagemDoErro(contexto, r.erro)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                LinkDeTexto(stringResource(R.string.chave_comprar)) { abrirTelegram(contexto) }
            }
        }
    }
}

@Composable
private fun BotaoAmarelo(texto: String, onClick: () -> Unit) {
    val forma = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().tvFocus(16.dp, scale = 1.03f).clip(forma).background(LabTheme.accent, forma)
            .clickable(onClick = onClick).padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(texto, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
}

@Composable
private fun BotaoVidro(texto: String, onClick: () -> Unit) {
    val forma = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().tvFocus(16.dp, scale = 1.03f).clip(forma).background(LabTheme.glass, forma)
            .border(0.5.dp, LabTheme.glassBorder, forma).clickable(onClick = onClick).padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(texto, color = LabTheme.text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

/** A página do app na loja de onde ele veio (Play ou Amazon): o app da loja primeiro, depois o site. */
private fun abrirNaLoja(contexto: Context) {
    val abriu = Loja.paginaDoApp(contexto.packageName).any { endereco ->
        try {
            contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(endereco)))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
    if (!abriu) Toast.makeText(contexto, R.string.pro_sem_play, Toast.LENGTH_LONG).show()
}

@Composable
private fun BotaoIcone(icone: androidx.compose.ui.graphics.vector.ImageVector, descricao: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).tvFocus(22.dp).clip(CircleShape).background(LabTheme.glass, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icone, descricao, tint = LabTheme.text, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun LinkDeTexto(texto: String, onClick: () -> Unit) {
    Text(
        texto, color = LabTheme.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.tvFocus(10.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

// ---------------------------------------------------------------------------
// Licenças
// ---------------------------------------------------------------------------

/**
 * O que vai dentro do app e sob que licença.
 *
 * A que obriga é a do libVLC (LGPL): quem distribui o app precisa dizer que
 * ele está lá, entregar o texto da licença e apontar onde está o código-fonte.
 * As outras (Apache, MIT, OFL) pedem o aviso e o texto, e é o que esta tela dá.
 */
private data class Componente(val nome: String, val autor: String, val licenca: String, val arquivo: String, val fonte: String)

private val componentes = listOf(
    Componente("LibVLC 3.7.5", "VideoLAN", "LGPL 2.1+", "LGPL-2.1.txt", "https://code.videolan.org/videolan/vlc-android"),
    Componente("smbj · asn-one", "Jeroen van Erp", "Apache 2.0", "Apache-2.0.txt", "https://github.com/hierynomus/smbj"),
    Componente("Bouncy Castle", "Legion of the Bouncy Castle", "MIT", "MIT.txt", "https://www.bouncycastle.org"),
    Componente("SLF4J", "QOS.ch", "MIT", "MIT.txt", "https://www.slf4j.org"),
    Componente("MBassador", "Benjamin Diedrichsen", "MIT", "MIT.txt", "https://github.com/bennidi/mbassador"),
    Componente("AndroidX · Jetpack Compose · Kotlin", "Google · JetBrains", "Apache 2.0", "Apache-2.0.txt", "https://developer.android.com/jetpack/androidx"),
    Componente("Tink · Gson", "Google", "Apache 2.0", "Apache-2.0.txt", "https://github.com/tink-crypto/tink-java"),
    Componente("Manrope", "The Manrope Project Authors", "SIL OFL 1.1", "OFL-1.1.txt", "https://github.com/googlefonts/manrope"),
)

@Composable
private fun TelaDeLicencas(onVoltar: () -> Unit) {
    val contexto = LocalContext.current
    var aberto by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = aberto != null) { aberto = null }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BotaoIcone(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.voltar)) {
                if (aberto != null) aberto = null else onVoltar()
            }
            Spacer(Modifier.width(12.dp))
            Text(
                aberto?.removeSuffix(".txt") ?: stringResource(R.string.licencas),
                color = LabTheme.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
            )
        }
        val arquivo = aberto
        if (arquivo != null) {
            val texto = remember(arquivo) {
                runCatching { contexto.assets.open("licencas/$arquivo").bufferedReader().readText() }
                    .getOrDefault("")
                    // Os textos vêm quebrados em 80 colunas, para terminal. Num
                    // celular isso vira linha longa, linha curta, linha longa:
                    // aqui cada parágrafo volta a ser um só e flui na largura.
                    .split(Regex("\n\\s*\n"))
                    .joinToString("\n\n") { paragrafo -> paragrafo.lines().joinToString(" ") { it.trim() } }
                    .trim()
            }
            Text(
                texto, color = LabTheme.muted, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            )
            return@Column
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.licencas_intro),
                color = LabTheme.muted, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            )
            componentes.forEach { c ->
                Column(
                    Modifier.fillMaxWidth().tvFocus(LabTheme.radiusCard, scale = 1.02f).clip(RoundedCornerShape(LabTheme.radiusCard))
                        .labCard().clickable { aberto = c.arquivo }.padding(16.dp),
                ) {
                    Text(c.nome, color = LabTheme.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("${c.autor} · ${c.licenca}", color = LabTheme.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(c.fonte, color = LabTheme.faint, fontSize = 12.sp)
                }
            }
        }
    }
}
