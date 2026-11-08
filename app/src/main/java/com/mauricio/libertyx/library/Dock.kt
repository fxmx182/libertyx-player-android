package com.mauricio.libertyx.library

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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Cloud
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

/**
 * Uma aba da dock. Início e Ajustes estão sempre nela; o resto — Arquivos,
 * Procurar, cada servidor SMB, WebDAV, Jellyfin ou Emby — entra e sai por
 * Ajustes › Dock (ver [DockEscolhida]). A [chave] é o que se guarda.
 */
sealed interface Aba {
    val chave: String

    data object Inicio : Aba { override val chave = "inicio" }
    data object Ajustes : Aba { override val chave = "ajustes" }
    data object Arquivos : Aba { override val chave = "arquivos" }
    data object Procurar : Aba { override val chave = "procurar" }
    /** Uma conta WebDAV, Jellyfin ou Emby, aberta direto no começo dela. */
    data class Conta(val id: String) : Aba { override val chave = "conta:$id" }
    /** Um servidor SMB, aberto nos compartilhamentos dele. */
    data class Smb(val id: String) : Aba { override val chave = "smb:$id" }

    companion object {
        fun de(chave: String): Aba? = when {
            chave == Inicio.chave -> Inicio
            chave == Ajustes.chave -> Ajustes
            chave == Arquivos.chave -> Arquivos
            chave == Procurar.chave -> Procurar
            chave.startsWith("conta:") -> Conta(chave.removePrefix("conta:"))
            chave.startsWith("smb:") -> Smb(chave.removePrefix("smb:"))
            else -> null
        }
    }
}

/**
 * O que a pessoa pôs na dock além de Início e Ajustes.
 *
 * Por padrão, nada: o Início já leva às pastas, e cada um escolhe o que quer a
 * um toque — o Jellyfin de casa, o Nextcloud, a busca. Cabem três: com Início
 * e Ajustes são cinco, o que a dock comporta sem apertar os rótulos.
 */
object DockEscolhida {
    const val MAXIMO = 3
    private const val CHAVE = "libertyx.dock.v1"

    /** Sobe a cada mudança, para quem desenha a dock e os Ajustes se refazer. */
    val versao = androidx.compose.runtime.mutableIntStateOf(0)

    fun itens(context: android.content.Context): List<Aba> =
        com.mauricio.libertyx.core.Prefs.get(context).getString(CHAVE, null).orEmpty()
            .split(',').filter { it.isNotBlank() }.mapNotNull { Aba.de(it) }

    /** Sem as contas e os servidores que já foram apagados, até o limite. */
    fun validos(context: android.content.Context): List<Aba> {
        val contas = com.mauricio.libertyx.rede.ContasDeRede.get(context)
        val smb = com.mauricio.libertyx.smb.SmbServerStore.get(context)
        return itens(context).filter {
            when (it) {
                is Aba.Conta -> contas.byId(it.id) != null
                is Aba.Smb -> smb.byId(it.id) != null
                else -> true
            }
        }.take(MAXIMO)
    }

    fun alternar(context: android.content.Context, aba: Aba) {
        val atuais = itens(context)
        val novos = if (aba in atuais) atuais - aba else atuais + aba
        com.mauricio.libertyx.core.Prefs.get(context).edit()
            .putString(CHAVE, novos.joinToString(",") { it.chave }).apply()
        versao.intValue++
    }
}

/** Como a aba aparece: o nome, e um ícone do sistema ou o símbolo do próprio servidor. */
class ItemDaDock(
    val aba: Aba,
    val rotulo: String,
    val icone: ImageVector? = null,
    val iconeAtivo: ImageVector? = null,
    @androidx.annotation.DrawableRes val imagem: Int? = null,
)

/** As abas visíveis, na ordem: Início, as escolhidas, Ajustes. */
@Composable
fun itensDaDock(): List<ItemDaDock> {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val versao = DockEscolhida.versao.intValue
    val contas = com.mauricio.libertyx.rede.ContasDeRede.get(contexto)
    val smb = com.mauricio.libertyx.smb.SmbServerStore.get(contexto)
    val inicio = ItemDaDock(Aba.Inicio, stringResource(R.string.aba_inicio), Icons.Outlined.PlayArrow, Icons.Filled.PlayArrow)
    val ajustes = ItemDaDock(Aba.Ajustes, stringResource(R.string.aba_ajustes), Icons.Outlined.Settings, Icons.Filled.Settings)
    val arquivos = stringResource(R.string.aba_arquivos)
    val procurar = stringResource(R.string.aba_procurar)
    val meio = androidx.compose.runtime.remember(versao) { DockEscolhida.validos(contexto) }.mapNotNull { aba ->
        when (aba) {
            Aba.Arquivos -> ItemDaDock(aba, arquivos, Icons.Outlined.Folder, Icons.Filled.Folder)
            Aba.Procurar -> ItemDaDock(aba, procurar, Icons.Outlined.Search, Icons.Filled.Search)
            is Aba.Conta -> contas.byId(aba.id)?.let { conta ->
                if (conta.tipo == com.mauricio.libertyx.rede.ContaDeRede.Tipo.JELLYFIN) {
                    ItemDaDock(aba, conta.nome, imagem = R.drawable.logo_jellyfin)
                } else {
                    ItemDaDock(aba, conta.nome, Icons.Outlined.Cloud, Icons.Filled.Cloud)
                }
            }
            is Aba.Smb -> smb.byId(aba.id)?.let { ItemDaDock(aba, it.name, imagem = R.drawable.logo_smb) }
            else -> null
        }
    }
    return listOf(inicio) + meio + ajustes
}

/** O ícone da aba: o símbolo do servidor vai nas cores dele; o do sistema, na cor do estado. */
@Composable
fun IconeDaAba(item: ItemDaDock, ativa: Boolean, cor: Color, tamanho: androidx.compose.ui.unit.Dp) {
    val imagem = item.imagem
    if (imagem != null) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(imagem), null, Modifier.size(tamanho),
        )
    } else {
        Icon((if (ativa) item.iconeAtivo else item.icone) ?: Icons.Outlined.Folder, null, tint = cor, modifier = Modifier.size(tamanho))
    }
}

/**
 * Na TV, a página do vídeo toma a tela inteira, como no app de TV do
 * Jellyfin: enquanto ela está aberta, a barra de abas do alto some (e
 * subir com o controle não cai mais nela). A página liga e desliga isto.
 */
object TelaCheiaNaTv {
    val ativa = androidx.compose.runtime.mutableStateOf(false)
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
    if (LocalDock.current) ScaffoldDefaults.contentWindowInsets.add(WindowInsets(bottom = if (dockBaixa()) 58.dp else 84.dp))
    else ScaffoldDefaults.contentWindowInsets

/**
 * Celular ou tablet deitado: a tela tem pouca altura, e a dock de pé (ícone
 * sobre o nome, 68 dp) tomava um quarto dela no celular. Deitada, ela fica
 * baixa, com o nome ao lado do ícone — largura é o que sobra.
 */
@Composable
fun dockBaixa(): Boolean {
    val tela = androidx.compose.ui.platform.LocalConfiguration.current
    return tela.screenWidthDp > tela.screenHeightDp
}

/**
 * A dock: uma pílula flutuando sobre o fim da tela, com até cinco abas.
 *
 * Ela substitui a fileira de botões redondos que ficava no alto. Lugares
 * fixos, sempre no mesmo canto do polegar, se acham sem procurar — os botões
 * de cima mudavam conforme a tela e ficavam longe da mão.
 */
@Composable
fun Dock(itens: List<ItemDaDock>, aba: Aba, onAba: (Aba) -> Unit, modifier: Modifier = Modifier) {
    val baixa = dockBaixa()
    val forma = RoundedCornerShape(if (baixa) 24.dp else 34.dp)
    Row(
        modifier.navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = if (baixa) 6.dp else 10.dp)
            .widthIn(max = if (baixa) 600.dp else 520.dp).fillMaxWidth().height(if (baixa) 46.dp else 68.dp)
            .shadow(20.dp, forma, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(forma)
            .background(Color(0xFF1D1D22).copy(alpha = 0.96f))
            .border(0.5.dp, LabTheme.glassBorder, forma)
            .padding(if (baixa) 4.dp else 5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itens.forEach { item ->
            val ativa = item.aba == aba
            val cor = if (ativa) LabTheme.accent else LabTheme.text.copy(alpha = 0.86f)
            val fundo = Modifier.weight(1f).fillMaxHeight()
                .clip(RoundedCornerShape(if (baixa) 20.dp else 29.dp))
                // A aba atual ganha uma pílula escura por baixo, como um
                // botão afundado — a cor sozinha some sob o sol.
                .background(if (ativa) Color.Black.copy(alpha = 0.55f) else Color.Transparent)
                .clickable { onAba(item.aba) }
            val rotulo = @Composable {
                Text(
                    item.rotulo, color = cor, fontSize = if (baixa) 12.sp else 11.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontWeight = if (ativa) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
            if (baixa) {
                Row(
                    fundo.padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconeDaAba(item, ativa, cor, 20.dp)
                    Spacer(Modifier.size(6.dp))
                    rotulo()
                }
            } else {
                Column(
                    fundo,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    IconeDaAba(item, ativa, cor, 24.dp)
                    Spacer(Modifier.height(2.dp))
                    rotulo()
                }
            }
        }
    }
}
