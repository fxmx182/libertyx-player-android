package com.mauricio.libertyx.tv

import androidx.compose.foundation.background
import com.mauricio.libertyx.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.library.Aba
import com.mauricio.libertyx.library.IconeDaAba
import com.mauricio.libertyx.library.ItemDaDock

/**
 * A barra da TV: uma fileira fina de ícones no alto, sem fundo e sem pílula.
 *
 * Ela ocupava uma faixa grossa da tela com cada aba escrita por extenso e a
 * atual afundada num fundo escuro — espaço que é da capa do filme. Agora
 * cada aba é só o ícone: a atual vem dourada, com um ponto embaixo, e o nome
 * aparece ao lado do ícone só quando o controle passa por ele — é aí que se
 * precisa saber o que é. A aba troca com o OK, não com o foco passando:
 * atravessar a fileira recarregaria cada tela do meio.
 */
@Composable
fun AbasDaTv(itens: List<ItemDaDock>, aba: Aba, onAba: (Aba) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_marca), null, Modifier.size(26.dp))
        Spacer(Modifier.width(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            itens.forEach { item ->
                val ativa = item.aba == aba
                var focado by remember { mutableStateOf(false) }
                val cor = when {
                    focado -> Color.Black
                    ativa -> LabTheme.accent
                    else -> LabTheme.text.copy(alpha = 0.72f)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.height(36.dp)
                            .onFocusChanged { focado = it.isFocused }
                            .clip(RoundedCornerShape(18.dp))
                            // Focada, a aba vira uma pílula clara com o nome — o
                            // que se lê da poltrona; parada, é só o ícone.
                            .background(if (focado) LabTheme.text else Color.Transparent)
                            .clickable { onAba(item.aba) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconeDaAba(item, ativa || focado, cor, 22.dp)
                        AnimatedVisibility(focado) {
                            Text(
                                item.rotulo, color = cor, fontSize = 14.sp, maxLines = 1,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    // O ponto da aba atual: diz onde se está sem pintar nada de escuro.
                    Box(
                        Modifier.padding(top = 3.dp).size(4.dp).clip(CircleShape)
                            .background(if (ativa) LabTheme.accent else Color.Transparent)
                    )
                }
            }
        }
    }
}
