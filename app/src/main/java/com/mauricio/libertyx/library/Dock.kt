package com.mauricio.libertyx.library

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.LabTheme

/** As quatro abas da dock, na ordem em que aparecem. */
enum class Aba(@StringRes val rotulo: Int, val icone: ImageVector, val iconeAtivo: ImageVector) {
    INICIO(R.string.aba_inicio, Icons.Outlined.PlayArrow, Icons.Filled.PlayArrow),
    ARQUIVOS(R.string.aba_arquivos, Icons.Outlined.Folder, Icons.Filled.Folder),
    PROCURAR(R.string.aba_procurar, Icons.Outlined.Search, Icons.Filled.Search),
    AJUSTES(R.string.aba_ajustes, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** Se a dock está por cima desta tela: no celular, sim; na TV não há dock. */
val LocalDock = staticCompositionLocalOf { false }

/**
 * As margens de um Scaffold que fica sob a dock.
 *
 * A lista termina logo acima dela: sem isso, a última pasta ou o último
 * servidor ficaria escondido atrás da pílula, sem como tocar.
 */
@Composable
fun margensSobADock(): WindowInsets =
    if (LocalDock.current) ScaffoldDefaults.contentWindowInsets.add(WindowInsets(bottom = 84.dp))
    else ScaffoldDefaults.contentWindowInsets

/**
 * A dock: uma pílula flutuando sobre o fim da tela, com as quatro abas.
 *
 * Ela substitui a fileira de botões redondos que ficava no alto. Quatro
 * lugares fixos, sempre no mesmo canto do polegar, se acham sem procurar —
 * os botões de cima mudavam conforme a tela e ficavam longe da mão.
 */
@Composable
fun Dock(aba: Aba, onAba: (Aba) -> Unit, modifier: Modifier = Modifier) {
    val forma = RoundedCornerShape(34.dp)
    Row(
        modifier.navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .widthIn(max = 520.dp).fillMaxWidth().height(68.dp)
            .shadow(20.dp, forma, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(forma)
            .background(Color(0xFF1D1D22).copy(alpha = 0.96f))
            .border(0.5.dp, LabTheme.glassBorder, forma)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Aba.entries.forEach { item ->
            val ativa = item == aba
            val cor = if (ativa) LabTheme.accent else LabTheme.text.copy(alpha = 0.86f)
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(29.dp))
                    // A aba atual ganha uma pílula escura por baixo, como um
                    // botão afundado — a cor sozinha some sob o sol.
                    .background(if (ativa) Color.Black.copy(alpha = 0.55f) else Color.Transparent)
                    .clickable { onAba(item) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (ativa) item.iconeAtivo else item.icone,
                    contentDescription = null, tint = cor, modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(item.rotulo), color = cor, fontSize = 11.sp, maxLines = 1,
                    fontWeight = if (ativa) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}
