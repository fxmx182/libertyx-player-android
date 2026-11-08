package com.mauricio.libertyx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import com.mauricio.libertyx.tv.AbasDaTv
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mauricio.libertyx.core.Device
import com.mauricio.libertyx.core.Pro
import com.mauricio.libertyx.pro.ProActivity
import com.mauricio.libertyx.player.IndiceDeFontes
import com.mauricio.libertyx.guia.Guia
import com.mauricio.libertyx.guia.GuiaDeBoasVindas
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.LibertyXTheme
import com.mauricio.libertyx.library.LibraryScreen
import com.mauricio.libertyx.library.Aba
import com.mauricio.libertyx.library.AjustesScreen
import com.mauricio.libertyx.library.ArquivosScreen
import com.mauricio.libertyx.library.Dock
import com.mauricio.libertyx.library.itensDaDock
import com.mauricio.libertyx.rede.ContasDeRede
import com.mauricio.libertyx.smb.SmbServerStore
import androidx.compose.runtime.key
import com.mauricio.libertyx.library.LocalDock
import com.mauricio.libertyx.library.ProcurarScreen
import com.mauricio.libertyx.rede.GrupoDeServidores
import com.mauricio.libertyx.tv.TvHomeScreen
import com.mauricio.libertyx.rede.AtalhoDeServidor
import com.mauricio.libertyx.rede.ContaDeRede
import com.mauricio.libertyx.rede.ContaScreen
import com.mauricio.libertyx.rede.NovoServidor
import com.mauricio.libertyx.smb.SmbBrowserScreen
import com.mauricio.libertyx.smb.SmbServer
import com.mauricio.libertyx.smb.SmbServersScreen
import androidx.compose.ui.res.stringResource

/**
 * A navegação do app, em poucas telas.
 *
 * Sem biblioteca de navegação: são poucos destinos e um deles é modal. Uma
 * dependência a mais para isso trocaria trinta linhas legíveis por um grafo,
 * um `NavController` e um contrato de argumentos serializados.
 */
private sealed interface Screen {
    /** As abas da dock (no celular) ou a tela da TV. */
    data object Library : Screen
    /** [cadastro]: já abrir o formulário deste tipo. [grupo]: que parte dos cadastros mostrar. */
    data class Servers(
        val cadastro: NovoServidor? = null,
        val grupo: GrupoDeServidores = GrupoDeServidores.TODOS,
    ) : Screen
    /** [volta]: de onde se veio — uma aba ou a lista de servidores. */
    data class Browser(val server: SmbServer, val volta: Screen) : Screen
    data class Conta(val conta: ContaDeRede, val volta: Screen) : Screen
}

class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        // O dia do teste pode ter virado desde a última vez.
        Pro.recalcular()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Só na primeira abertura: sem isto, o primeiro vídeo esperava o VLC
        // indexar as fontes do aparelho.
        IndiceDeFontes.preparar(this)
        setContent {
            LibertyXTheme {
                Surface(color = LabTheme.background, modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App() {
    var tela by remember { mutableStateOf<Screen>(Screen.Library) }
    var chaveDaAba by rememberSaveable { mutableStateOf(Aba.Inicio.chave) }
    // A dock é montada em Ajustes › Dock: Início, até três escolhidas e Ajustes.
    val itens = itensDaDock()
    // A aba aberta saiu da dock (ou o servidor foi apagado): volta ao Início.
    val aba = Aba.de(chaveDaAba)?.takeIf { a -> itens.any { it.aba == a } } ?: Aba.Inicio
    val mudarAba = { nova: Aba -> chaveDaAba = nova.chave }

    // O botão "voltar" do Android tem que voltar dentro do app, e não sair
    // dele. Sem isto, sair da tela de servidores fecha o LibertyX — e como a
    // navegação é um estado e não uma pilha de atividades, o sistema não tem
    // como adivinhar sozinho.
    BackHandler(enabled = tela is Screen.Servers) { tela = Screen.Library }

    val contexto = LocalContext.current
    val naTv = Device.isTv(contexto)

    // O guia vem antes do pedido de permissão: quem acabou de instalar vê
    // primeiro o que o app faz, e só depois o Android perguntando coisas. Na
    // TV não há gesto para ensinar — o controle remoto já é o guia.
    var mostrandoGuia by rememberSaveable { mutableStateOf(!naTv && Guia.precisaMostrar(contexto)) }
    if (mostrandoGuia) {
        GuiaDeBoasVindas(onFim = { Guia.marcarVisto(contexto); mostrandoGuia = false })
        return
    }

    // A rede é Pro: depois do teste, a porta leva à tela de compra.
    val abrirServidores = { grupo: GrupoDeServidores ->
        if (ProActivity.exigir(contexto, Pro.Recurso.REDE)) tela = Screen.Servers(grupo = grupo)
    }
    val abrirAtalho = { atalho: AtalhoDeServidor ->
        if (ProActivity.exigir(contexto, Pro.Recurso.REDE)) {
            tela = when (atalho) {
                is AtalhoDeServidor.Smb -> Screen.Browser(atalho.servidor, volta = Screen.Library)
                is AtalhoDeServidor.Conta -> Screen.Conta(atalho.conta, volta = Screen.Library)
            }
        }
    }

    // A dock fica por cima de todas as telas, como no iPhone: dentro de um
    // servidor ou de uma pasta da rede, as quatro abas seguem a um toque.
    // Tocar numa aba fecha a tela da rede e leva à raiz dela.
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDock provides !naTv) {
            when (val atual = tela) {
                // As mesmas quatro abas nos dois: na dock, para o dedo, e numa
                // fileira no alto da TV, para o controle remoto.
                is Screen.Library -> {
                    // Voltar numa aba que não é a primeira leva ao Início, e só de lá
                    // o app fecha — como em todo app de abas do Android.
                    BackHandler(enabled = aba != Aba.Inicio) { mudarAba(Aba.Inicio) }
                    val conteudo = @Composable {
                        TelaDaAba(
                            aba, naTv, onAba = mudarAba,
                            abrirServidores = abrirServidores, abrirAtalho = abrirAtalho,
                            // Na TV não há guia: o controle remoto já é o guia.
                            onGuia = if (naTv) null else ({ mostrandoGuia = true }),
                        )
                    }
                    if (naTv) {
                        Column(Modifier.fillMaxSize().background(LabTheme.background)) {
                            AbasDaTv(itens, aba, onAba = mudarAba)
                            Box(Modifier.weight(1f).fillMaxWidth()) { conteudo() }
                        }
                    } else {
                        conteudo()
                    }
                }
                is Screen.Servers -> SmbServersScreen(
                    onBack = { tela = Screen.Library },
                    onOpen = { servidor -> tela = Screen.Browser(servidor, volta = atual) },
                    onOpenConta = { conta -> tela = Screen.Conta(conta, volta = atual) },
                    cadastroInicial = atual.cadastro,
                    grupo = atual.grupo,
                )
                is Screen.Conta -> ContaScreen(
                    conta = atual.conta,
                    // Volta sem o pedido de cadastro: senão o formulário reabriria.
                    onBack = { tela = (atual.volta as? Screen.Servers)?.copy(cadastro = null) ?: atual.volta },
                )
                is Screen.Browser -> SmbBrowserScreen(
                    server = atual.server,
                    onBack = { tela = (atual.volta as? Screen.Servers)?.copy(cadastro = null) ?: atual.volta },
                )
            }
        }
        if (!naTv) {
            Dock(
                itens, aba,
                onAba = { mudarAba(it); tela = Screen.Library },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * A tela de cada aba, a mesma no celular e na TV — menos o Início, que na TV
 * é de faixas, para o controle remoto.
 */
@Composable
private fun TelaDaAba(
    aba: Aba,
    naTv: Boolean,
    onAba: (Aba) -> Unit,
    abrirServidores: (GrupoDeServidores) -> Unit,
    abrirAtalho: (AtalhoDeServidor) -> Unit,
    onGuia: (() -> Unit)?,
) {
    val contexto = LocalContext.current
    when (aba) {
        // Sem a permissão só as abas de vídeo do aparelho trancam:
        // os Ajustes e a busca nos servidores seguem à mão. Na TV nem elas,
        // porque lá o acervo mora no servidor.
        Aba.Inicio -> if (naTv) {
            ComPermissao(bloqueia = false) {
                TvHomeScreen(onOpenServers = { abrirServidores(GrupoDeServidores.TODOS) }, onAtalho = abrirAtalho)
            }
        } else {
            ComPermissao {
                LibraryScreen(
                    onOpenServers = { abrirServidores(GrupoDeServidores.SERVIDORES) },
                    onAtalho = abrirAtalho,
                )
            }
        }
        Aba.Arquivos -> ComPermissao(bloqueia = !naTv) {
            ArquivosScreen(onAtalho = abrirAtalho, onAdicionarServidor = { onAba(Aba.Ajustes) })
        }
        Aba.Procurar -> ProcurarScreen()
        // Um servidor na dock abre direto nele; voltar da raiz leva ao Início.
        is Aba.Conta -> ContasDeRede.get(contexto).byId(aba.id)?.let { conta ->
            key(aba.chave) { ContaScreen(conta, onBack = { onAba(Aba.Inicio) }) }
        }
        is Aba.Smb -> SmbServerStore.get(contexto).byId(aba.id)?.let { servidor ->
            key(aba.chave) { SmbBrowserScreen(server = servidor, onBack = { onAba(Aba.Inicio) }) }
        }
        Aba.Ajustes -> AjustesScreen(
            onServidores = { abrirServidores(GrupoDeServidores.SERVIDORES) },
            onWebDav = { abrirServidores(GrupoDeServidores.WEBDAV) },
            onGuia = onGuia,
        )
    }
}

/**
 * A permissão de ler vídeo, pedida onde ela é usada.
 *
 * O nome dela mudou no Android 13, e essa é a única complicação: até o 12 é a
 * permissão genérica de armazenamento; do 13 em diante, uma específica para
 * vídeo. Pedir a errada devolve uma negativa que o sistema nem mostra ao
 * usuário — o app só fica com a lista vazia, sem explicação.
 */
@Composable
private fun ComPermissao(bloqueia: Boolean = true, conteudo: @Composable () -> Unit) {
    val contexto = LocalContext.current
    val permissao = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var concedida by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(contexto, permissao) == PackageManager.PERMISSION_GRANTED
        )
    }

    val pedido = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { resposta -> concedida = resposta }

    LaunchedEffect(Unit) { if (!concedida) pedido.launch(permissao) }

    // Na televisão a permissão é pedida do mesmo jeito, mas a negativa não
    // tranca a porta: numa caixinha de TV quase não há vídeo local, e o que
    // interessa — o servidor de casa — não depende dela. Trancar tudo aqui
    // deixaria o usuário sem o acervo por causa de uma pasta vazia.
    if (concedida || !bloqueia) {
        conteudo()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.permissao_explica),
                style = MaterialTheme.typography.bodyMedium,
                color = LabTheme.muted,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { pedido.launch(permissao) }, modifier = Modifier.padding(top = 20.dp)) {
                Text(stringResource(R.string.permitir))
            }
            // Quem recusou — e o revisor da loja num aparelho vazio — ainda
            // tem o que tocar sem precisar de vídeo nenhum aqui.
            Box(Modifier.padding(top = 36.dp).widthIn(max = 460.dp)) {
                com.mauricio.libertyx.library.CartaoDeDemonstracao()
            }
        }
    }
}
