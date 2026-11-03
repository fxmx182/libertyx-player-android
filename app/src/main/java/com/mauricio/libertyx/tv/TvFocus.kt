package com.mauricio.libertyx.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.mauricio.libertyx.core.Device
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mauricio.libertyx.core.LabTheme

/**
 * O realce de foco — a peça que faz uma tela de TV ser usável.
 *
 * No celular o dedo aponta para onde vai agir, e o app não precisa dizer nada.
 * A três metros de distância, com um controle remoto, **o foco é a única coisa
 * que diz onde você está**: sem ele o usuário aperta uma direção e não sabe o
 * que se moveu. Por isso o realce é deliberadamente exagerado — borda da cor da
 * marca, e um crescimento que se percebe de longe.
 *
 * No celular isto não aparece nunca: dedo não dá foco a nada.
 */
fun Modifier.tvFocus(
    radius: Dp = 14.dp,
    scale: Float = 1.06f,
): Modifier = composed {
    var focado by remember { mutableStateOf(false) }

    val escala by animateFloatAsState(
        targetValue = if (focado) scale else 1f,
        label = "escala do foco",
    )
    val cor by animateColorAsState(
        targetValue = if (focado) LabTheme.accent else Color.Transparent,
        label = "borda do foco",
    )

    this
        .onFocusChanged { focado = it.isFocused }
        .scale(escala)
        .border(if (focado) 2.5.dp else 0.dp, cor, RoundedCornerShape(radius))
}

/**
 * Deixa as setas andarem por um formulário inteiro.
 *
 * O Compose entrega as setas ao cursor do campo — comportamento certo num
 * teclado de computador, e uma armadilha num controle remoto: o usuário entra
 * no primeiro campo do formulário e **não sai mais dele**. Como os campos aqui
 * são todos de uma linha só, não há cursor vertical para mover, e cima/baixo
 * podem significar o que significam no resto da tela: trocar de campo.
 *
 * `onPreviewKeyEvent` porque a interceptação precisa acontecer antes de quem
 * tem o foco consumir a tecla — depois já é tarde. E aplicado no **contêiner**,
 * não em cada campo: a caixa de seleção do meio do formulário também prendia o
 * foco, e cobrir um por um deixa sempre um de fora.
 */
fun Modifier.setasTrocamDeCampo(): Modifier = composed {
    val foco = LocalFocusManager.current
    onPreviewKeyEvent { evento ->
        if (evento.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        // Devolver o resultado do movimento, e não `true` fixo, é o que
        // permite chegar aos botões: quando não há mais para onde descer, a
        // tecla segue o caminho normal em vez de ser engolida aqui.
        // `Next`/`Previous`, e não `Down`/`Up`: a busca geométrica falha ao sair
        // de dentro de uma linha — a caixa de seleção do meio do formulário
        // prendia o foco porque o próximo campo é irmão da linha, não dela. Num
        // formulário vertical a ordem de declaração é a ordem visual, então
        // seguir a ordem acerta sempre.
        when (evento.key) {
            Key.DirectionDown -> foco.moveFocus(FocusDirection.Next)
            Key.DirectionUp -> foco.moveFocus(FocusDirection.Previous)
            else -> false
        }
    }
}

/** Fonte de interação sem ondulação: o efeito de toque não faz sentido na TV. */
@Composable
fun rememberTvInteraction(): MutableInteractionSource =
    remember { MutableInteractionSource() }

/**
 * O clique, e o "segurar" que abre as opções do cartão.
 *
 * No controle remoto, segurar o OK é o toque longo do celular — é assim que o
 * app do Jellyfin e a tela inicial da própria TV abrem o menu de um item. A
 * tecla de menu, nos controles que a têm, faz o mesmo: segurar um botão não é
 * coisa que todo mundo adivinha.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.cliqueComOpcoes(onClick: () -> Unit, onOpcoes: () -> Unit): Modifier =
    onPreviewKeyEvent { evento ->
        if (evento.key != Key.Menu) return@onPreviewKeyEvent false
        if (evento.type == KeyEventType.KeyUp) onOpcoes()
        true
    }.combinedClickable(onClick = onClick, onLongClick = onOpcoes)

/**
 * A rolagem das faixas na TV: o foco fica num lugar fixo e a faixa desliza
 * por baixo dele, como no app do Jellyfin e na tela inicial do Google TV.
 *
 * A rolagem padrão anda só o mínimo para o item focado caber — e o cartão
 * focado cresce (ver [tvFocus]), então a cada clique a faixa ajustava uns
 * pixels, e a lista de faixas também, para caber o cartão maior: as fileiras
 * davam saltinhos sozinhas. Com o lugar fixo, cada clique move exatamente um
 * cartão, e mover para o lado não mexe nada na vertical.
 *
 * [inicio] é onde o foco para, contado do começo da área visível. Vale para
 * a rolagem que estiver logo dentro; uma faixa dentro de uma lista declara a
 * sua. No celular não muda nada: o dedo rola, e foco não há.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RolagemDeTv(inicio: Dp, conteudo: @Composable () -> Unit) {
    if (!Device.isTv(LocalContext.current)) {
        conteudo()
        return
    }
    val px = with(LocalDensity.current) { inicio.toPx() }
    val regra = remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - px
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides regra, content = conteudo)
}

/**
 * A faixa que só rola quando o foco chega à borda, como no app do Jellyfin.
 *
 * Com o foco preso num lugar (ver [RolagemDeTv]) a faixa inteira deslizava a
 * cada clique — parecia que as fileiras se mexiam sozinhas. Aqui o foco anda
 * pelos cartões que já estão na tela e a faixa fica parada; ao chegar à borda,
 * ela anda o bastante para o cartão inteiro aparecer.
 *
 * A [margem] é a folga da borda: o cartão focado cresce (ver [tvFocus]), e sem
 * folga o crescimento passava da borda e a faixa ajustava uns pixels a cada
 * clique — os saltinhos.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RolagemDeTvNasBordas(margem: Dp, conteudo: @Composable () -> Unit) {
    if (!Device.isTv(LocalContext.current)) {
        conteudo()
        return
    }
    val px = with(LocalDensity.current) { margem.toPx() }
    val regra = remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val fim = offset + size
                return when {
                    offset < px -> offset - px
                    fim > containerSize - px -> fim - (containerSize - px)
                    else -> 0f
                }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides regra, content = conteudo)
}
