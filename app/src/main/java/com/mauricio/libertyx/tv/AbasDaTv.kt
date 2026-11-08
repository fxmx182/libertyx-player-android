package com.mauricio.libertyx.tv

import androidx.compose.foundation.background
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
import com.mauricio.libertyx.library.Marca

/**
 * A dock da TV: as mesmas quatro abas do celular, numa fileira no alto.
 *
 * No alto, e não embaixo, porque é onde toda tela de TV põe as seções (a do
 * Google TV, a do Jellyfin): o controle sobe até elas com a seta para cima,
 * e o conteúdo começa logo abaixo, sem uma faixa fixa tapando o fim da tela.
 * A aba troca com o OK, e não só com o foco passando: atravessar a fileira
 * a caminho de outra aba recarregaria cada tela do meio.
 */
@Composable
fun AbasDaTv(itens: List<ItemDaDock>, aba: Aba, onAba: (Aba) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Marca()
        Spacer(Modifier.width(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itens.forEach { item ->
                val ativa = item.aba == aba
                val cor = if (ativa) LabTheme.accent else LabTheme.text.copy(alpha = 0.86f)
                val forma = RoundedCornerShape(22.dp)
                Row(
                    Modifier.height(44.dp)
                        .tvFocus(22.dp, scale = 1.06f)
                        .clip(forma)
                        // A aba atual fica afundada, como na dock do celular.
                        .background(if (ativa) Color.Black.copy(alpha = 0.55f) else Color.Transparent)
                        .clickable { onAba(item.aba) }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconeDaAba(item, ativa, cor, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.rotulo, color = cor, fontSize = 15.sp, maxLines = 1,
                        fontWeight = if (ativa) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
