package com.mauricio.libertyx.rede

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.mauricio.libertyx.library.margensSobADock
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.Device
import com.mauricio.libertyx.core.Escala
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.library.MenuDoItem
import com.mauricio.libertyx.library.OpcaoDoMenu
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.core.labCard
import com.mauricio.libertyx.library.CartaoDeVideo
import com.mauricio.libertyx.library.FundoDeCapa
import com.mauricio.libertyx.library.LibraryLayout
import com.mauricio.libertyx.library.LibraryOptions
import com.mauricio.libertyx.library.LinhaDeVideo
import com.mauricio.libertyx.library.OpcoesDaBiblioteca
import com.mauricio.libertyx.library.Thumbnails
import com.mauricio.libertyx.player.Playback
import com.mauricio.libertyx.smb.SmbServer
import com.mauricio.libertyx.smb.SmbServerStore
import com.mauricio.libertyx.tv.setasTrocamDeCampo
import com.mauricio.libertyx.tv.tvFocus
import com.mauricio.libertyx.tv.RolagemDeTvNasBordas
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

val ContaDeRede.Tipo.icone: ImageVector
    get() = when (this) {
        ContaDeRede.Tipo.WEBDAV -> Icons.Filled.Cloud
        ContaDeRede.Tipo.JELLYFIN -> Icons.Filled.VideoLibrary
        ContaDeRede.Tipo.EMBY -> Icons.Filled.Tv
    }

// MARK: Escolha do tipo

/** Um cadastro pedido de fora da tela de servidores — o "+" da biblioteca. `null` é o SMB. */
data class NovoServidor(val tipo: ContaDeRede.Tipo?)

/** Um servidor salvo, como atalho na tela inicial. */
sealed interface AtalhoDeServidor {
    val chave: String
    val nome: String
    val tipo: String
    val icone: ImageVector

    data class Smb(val servidor: SmbServer) : AtalhoDeServidor {
        override val chave get() = "smb:" + servidor.id
        override val nome get() = servidor.name
        override val tipo get() = "SMB"
        override val icone get() = Icons.Filled.Dns
    }

    data class Conta(val conta: ContaDeRede) : AtalhoDeServidor {
        override val chave get() = "conta:" + conta.id
        override val nome get() = conta.nome
        override val tipo get() = conta.tipo.nome
        override val icone get() = conta.tipo.icone
    }

    companion object {
        fun todos(context: android.content.Context): List<AtalhoDeServidor> =
            SmbServerStore.get(context).servers.map(::Smb) + ContasDeRede.get(context).contas.map(::Conta)
    }
}

/**
 * Qual servidor cadastrar. `null` é o SMB, que tem formulário próprio.
 */
/**
 * Como os Ajustes separam os cadastros: os servidores de casa (SMB, Jellyfin,
 * Emby) de um lado, o WebDAV do outro. A TV continua vendo tudo numa lista só.
 */
enum class GrupoDeServidores {
    TODOS, SERVIDORES, WEBDAV;

    fun mostra(tipo: ContaDeRede.Tipo): Boolean = when (this) {
        TODOS -> true
        SERVIDORES -> tipo != ContaDeRede.Tipo.WEBDAV
        WEBDAV -> tipo == ContaDeRede.Tipo.WEBDAV
    }

    val temSmb: Boolean get() = this != WEBDAV
}

@Composable
fun EscolhaDeTipoDeServidor(
    onEscolher: (ContaDeRede.Tipo?) -> Unit,
    onCancel: () -> Unit,
    grupo: GrupoDeServidores = GrupoDeServidores.TODOS,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = LabTheme.surface,
        title = { Text(stringResource(R.string.tipo_de_servidor)) },
        text = {
            Column {
                if (grupo.temSmb) {
                    OpcaoDeTipo(Icons.Filled.Dns, stringResource(R.string.tipo_smb)) { onEscolher(null) }
                }
                if (grupo.mostra(ContaDeRede.Tipo.WEBDAV)) {
                    OpcaoDeTipo(Icons.Filled.Cloud, stringResource(R.string.tipo_webdav)) { onEscolher(ContaDeRede.Tipo.WEBDAV) }
                }
                if (grupo.mostra(ContaDeRede.Tipo.JELLYFIN)) {
                    OpcaoDeTipo(Icons.Filled.VideoLibrary, "Jellyfin") { onEscolher(ContaDeRede.Tipo.JELLYFIN) }
                    OpcaoDeTipo(Icons.Filled.Tv, "Emby") { onEscolher(ContaDeRede.Tipo.EMBY) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancelar)) } },
    )
}

@Composable
private fun OpcaoDeTipo(icone: ImageVector, texto: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .tvFocus(LabTheme.radiusSmall, scale = 1.02f)
            .clip(RoundedCornerShape(LabTheme.radiusSmall))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icone, null, tint = LabTheme.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(texto, color = LabTheme.text, fontSize = 15.sp)
    }
}

// MARK: Cadastro

/**
 * Formulário de conta WebDAV, Jellyfin ou Emby.
 *
 * Salvar já testa a conta: um endereço errado descoberto só na hora de abrir a
 * pasta parece defeito do app. No Jellyfin/Emby salvar é também entrar — o que
 * se guarda é o token, não a senha.
 */
@Composable
fun EditorDeConta(
    tipo: ContaDeRede.Tipo,
    conta: ContaDeRede?,
    onCancel: () -> Unit,
    onSalvo: () -> Unit,
) {
    val contexto = LocalContext.current
    val loja = remember { ContasDeRede.get(contexto) }
    val escopo = rememberCoroutineScope()

    var nome by remember { mutableStateOf(conta?.nome.orEmpty()) }
    var endereco by remember { mutableStateOf(conta?.endereco.orEmpty()) }
    var usuario by remember { mutableStateOf(conta?.usuario.orEmpty()) }
    var senha by remember {
        mutableStateOf(if (conta != null && tipo == ContaDeRede.Tipo.WEBDAV) loja.segredo(conta).orEmpty() else "")
    }
    var mostrarSenha by remember { mutableStateOf(false) }
    var testando by remember { mutableStateOf(false) }
    var falha by remember { mutableStateOf<String?>(null) }
    /** Os usuários que o servidor mostra na tela de entrada dele. */
    var usuarios by remember { mutableStateOf<List<String>>(emptyList()) }
    var escolhendoUsuario by remember { mutableStateOf(false) }
    /** O código do Quick Connect enquanto espera a aprovação. */
    var codigoQuick by remember { mutableStateOf<String?>(null) }
    var tarefaQuick by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun candidatos(): List<String> {
        val texto = endereco.trim()
        return if (texto.startsWith("http://", true) || texto.startsWith("https://", true)) listOf(texto)
        else listOf("https://$texto", "http://$texto")
    }

    // Espera a pessoa parar de digitar o endereço e pergunta quem pode entrar.
    LaunchedEffect(endereco) {
        usuarios = emptyList()
        if (!tipo.ehServidorDeMidia || endereco.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        for (candidato in candidatos()) {
            val nomes = ServidorDeMidia.usuariosVisiveis(ContaDeRede(tipo = tipo, nome = "", endereco = candidato))
            if (nomes.isNotEmpty()) { usuarios = nomes; break }
        }
    }

    /**
     * Entrar sem digitar a senha: o app mostra um código, e alguém já
     * conectado a este servidor (no app dele ou no navegador) aprova.
     */
    fun quickConnect() {
        falha = null
        tarefaQuick?.cancel()
        tarefaQuick = escopo.launch {
            val lista = candidatos()
            for ((i, candidato) in lista.withIndex()) {
                val provisoria = (conta ?: ContaDeRede(tipo = tipo, nome = "", endereco = "")).copy(endereco = candidato)
                val resultado = runCatching {
                    val (codigo, segredo) = ServidorDeMidia.iniciarQuickConnect(provisoria)
                    codigoQuick = codigo
                    val (nomeDoUsuario, idUsuario, token) = ServidorDeMidia.esperarQuickConnect(provisoria, segredo)
                    val nova = provisoria.copy(
                        usuario = nomeDoUsuario, idNoServidor = idUsuario,
                        nome = nome.trim().ifEmpty { provisoria.enderecoCurto },
                    )
                    loja.salvar(nova, token)
                }
                val erro = resultado.exceptionOrNull()
                if (erro == null) { codigoQuick = null; onSalvo(); return@launch }
                if (erro is CancellationException) { codigoQuick = null; throw erro }
                if (erro is java.io.IOException && i < lista.lastIndex) continue
                falha = erro.message ?: erro.javaClass.simpleName
                codigoQuick = null
                return@launch
            }
        }
    }

    val exemplo = if (tipo == ContaDeRede.Tipo.WEBDAV) "https://nuvem.exemplo.com/remote.php/dav/files/usuario/"
    else "http://192.168.0.10:8096"
    val valido = endereco.isNotBlank() && (!tipo.ehServidorDeMidia || usuario.isNotBlank())

    suspend fun salvarEm(texto: String) {
        var nova = (conta ?: ContaDeRede(tipo = tipo, nome = "", endereco = ""))
            .copy(endereco = texto, usuario = usuario.trim())
        nova = nova.copy(nome = nome.trim().ifEmpty { nova.enderecoCurto })

        if (tipo == ContaDeRede.Tipo.WEBDAV) {
            nova = testarWebDav(nova, senha)
            loja.salvar(nova, senha)
            return
        }
        // Editar só o apelido não pede a senha de novo; trocar o servidor ou o
        // usuário pede, porque o token antigo não vale lá.
        val mesmoAcesso = conta != null && conta.endereco == nova.endereco && conta.usuario == nova.usuario
        if (mesmoAcesso && senha.isEmpty()) {
            loja.salvar(nova, null)
        } else {
            val (idUsuario, token) = ServidorDeMidia.entrar(nova, senha)
            loja.salvar(nova.copy(idNoServidor = idUsuario), token)
        }
    }

    fun salvar() {
        testando = true
        falha = null
        escopo.launch {
            // Sem esquema não dá para saber: `maujellyfin.uk` atrás do
            // Cloudflare só fala HTTPS, e `192.168.0.10:8096` em casa só fala
            // HTTP. Tenta HTTPS primeiro e cai para HTTP só se nem conectar —
            // erro de senha num deles já é resposta e encerra a tentativa.
            val texto = endereco.trim()
            val candidatos = if (texto.startsWith("http://", true) || texto.startsWith("https://", true)) {
                listOf(texto)
            } else {
                listOf("https://$texto", "http://$texto")
            }
            for ((i, candidato) in candidatos.withIndex()) {
                val resultado = runCatching { salvarEm(candidato) }
                val erro = resultado.exceptionOrNull()
                if (erro == null) {
                    onSalvo()
                    break
                }
                if (erro is java.io.IOException && i < candidatos.lastIndex) continue
                falha = erro.message ?: erro.javaClass.simpleName
                break
            }
            testando = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!testando) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 24.dp),
        containerColor = LabTheme.surface,
        title = {
            Text(
                if (conta == null) stringResource(R.string.novo_servidor_tipo, tipo.nome)
                else stringResource(R.string.editar_servidor)
            )
        },
        text = {
            // O conteúdo rola: numa tela deitada os últimos campos ficariam
            // abaixo da borda, e o que não dá para focar não dá para digitar.
            Column(Modifier.verticalScroll(rememberScrollState()).setasTrocamDeCampo()) {
                OutlinedTextField(
                    value = nome, onValueChange = { nome = it },
                    label = { Text(stringResource(R.string.nome)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = endereco, onValueChange = { endereco = it },
                    label = { Text(stringResource(R.string.endereco_url)) },
                    placeholder = { Text(exemplo, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = usuario, onValueChange = { usuario = it },
                    label = { Text(stringResource(R.string.usuario)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (usuarios.isNotEmpty()) {
                    TextButton(onClick = { escolhendoUsuario = true }) { Text(stringResource(R.string.escolher_usuario)) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = senha, onValueChange = { senha = it },
                    label = {
                        Text(
                            stringResource(
                                if (conta != null && tipo.ehServidorDeMidia) R.string.senha_entrar_de_novo
                                else R.string.senha
                            )
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (mostrarSenha) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (mostrarSenha) KeyboardType.Text else KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = mostrarSenha, onCheckedChange = { mostrarSenha = it })
                    Text(stringResource(R.string.mostrar_senha), color = LabTheme.text, fontSize = 13.sp)
                }
                if (tipo.ehServidorDeMidia) {
                    val codigo = codigoQuick
                    if (codigo != null) {
                        Text(stringResource(R.string.qc_aprove), color = LabTheme.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            codigo, color = LabTheme.text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 6.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.qc_esperando), color = LabTheme.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { tarefaQuick?.cancel(); codigoQuick = null }) { Text(stringResource(R.string.cancelar)) }
                        }
                    } else {
                        TextButton(enabled = endereco.isNotBlank() && !testando, onClick = ::quickConnect) {
                            Text(stringResource(R.string.qc_entrar))
                        }
                        Text(stringResource(R.string.qc_explica), color = LabTheme.faint, fontSize = 11.5.sp)
                    }
                }
                falha?.let {
                    Text(it, color = Color(0xFFFFA94D), fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                Text(
                    stringResource(
                        if (tipo == ContaDeRede.Tipo.WEBDAV) R.string.conta_webdav_explica
                        else R.string.conta_midia_explica
                    ),
                    color = LabTheme.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            if (testando) {
                CircularProgressIndicator(Modifier.size(22.dp).padding(end = 8.dp), strokeWidth = 2.dp)
            } else {
                TextButton(enabled = valido, onClick = ::salvar) { Text(stringResource(R.string.salvar)) }
            }
        },
        dismissButton = {
            TextButton(enabled = !testando, onClick = { tarefaQuick?.cancel(); onCancel() }) { Text(stringResource(R.string.cancelar)) }
        },
    )

    if (escolhendoUsuario) {
        MenuDoItem(stringResource(R.string.escolher_usuario), { escolhendoUsuario = false }) {
            usuarios.forEachIndexed { i, nomeDoUsuario ->
                OpcaoDoMenu(nomeDoUsuario, primeira = i == 0) {
                    usuario = nomeDoUsuario
                    escolhendoUsuario = false
                }
            }
        }
    }
}

/**
 * Lista a pasta do endereço; se ele for só o servidor, tenta o caminho do
 * Nextcloud antes de desistir.
 */
private suspend fun testarWebDav(conta: ContaDeRede, senha: String): ContaDeRede {
    val cliente = WebDav(conta, senha)
    return runCatching { cliente.listar(cliente.raiz); conta }.getOrElse { erro ->
        if (cliente.raiz != "/" || conta.usuario.isEmpty()) throw erro
        val nextcloud = conta.copy(
            endereco = conta.endereco.trimEnd('/') + "/remote.php/dav/files/" + WebDav.escapar(conta.usuario) + "/"
        )
        val outro = WebDav(nextcloud, senha)
        runCatching { outro.listar(outro.raiz) }.getOrElse { throw erro }
        nextcloud
    }
}

// MARK: Navegação

/** Um nível dentro da conta: pasta do WebDAV, ou biblioteca, série ou temporada. */
private sealed interface Nivel {
    val nome: String

    data class Pasta(override val nome: String, val caminho: String) : Nivel
    data class Bibliotecas(override val nome: String) : Nivel
    data class Item(override val nome: String, val item: ServidorDeMidia.Item) : Nivel
    /** Jellyfin e Emby: a página de um filme ou episódio, da série, da pessoa, e os canais. */
    data class Video(override val nome: String, val item: ServidorDeMidia.Item) : Nivel
    /** [item]: a série como veio na lista, para a capa na pasta. */
    data class Serie(override val nome: String, val id: String, val item: ServidorDeMidia.Item? = null) : Nivel
    data class Pessoa(override val nome: String, val pessoa: ServidorDeMidia.Pessoa) : Nivel
    data class Canais(override val nome: String) : Nivel
}

/** Para onde leva um item do servidor: vídeo e série têm página; o resto é pasta. */
private fun nivelDe(item: ServidorDeMidia.Item): Nivel = when {
    item.ehVideo -> Nivel.Video(item.titulo, item)
    item.tipo == "Series" -> Nivel.Serie(item.nome, item.id, item)
    else -> Nivel.Item(item.titulo, item)
}

private data class Conteudo(
    val pastas: List<Pair<Nivel, ImageVector>>,
    val videos: List<MediaItem>,
    /** Jellyfin e Emby: o item do servidor por trás de cada vídeo, para o "Já reproduzido". */
    val doServidor: Map<String, ServidorDeMidia.Item> = emptyMap(),
    val ordemDoServidor: Boolean = false,
)

/**
 * Dentro de uma conta WebDAV, Jellyfin ou Emby — o mesmo desenho das pastas
 * SMB: grade ou lista, as mesmas opções, a mesma miniatura.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContaScreen(conta: ContaDeRede, onBack: () -> Unit, abrir: ServidorDeMidia.Item? = null) {
    val contexto = LocalContext.current
    val loja = remember { ContasDeRede.get(contexto) }
    val naTv = remember { Device.isTv(contexto) }
    val opcoes = remember { LibraryOptions(contexto) }
    var mostrandoOpcoes by remember { mutableStateOf(false) }

    val inicio: Nivel = remember(conta.id) {
        if (conta.tipo == ContaDeRede.Tipo.WEBDAV) Nivel.Pasta(conta.nome, WebDav(conta, null).raiz)
        else Nivel.Bibliotecas(conta.nome)
    }
    // [abrir]: vindo da busca, a conta abre já na página do item; voltar sai.
    var pilha by remember(conta.id, abrir?.id) { mutableStateOf(listOfNotNull(abrir?.let(::nivelDe) ?: inicio)) }
    val atual = pilha.last()

    var conteudo by remember { mutableStateOf<Conteudo?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var menuDe by remember { mutableStateOf<ServidorDeMidia.Item?>(null) }
    // Jellyfin e Emby: as séries e temporadas ganham capa (ver `CapaDePastaDoServidor`).
    val servidorDaConta = remember(conta.id) {
        if (conta.tipo == ContaDeRede.Tipo.WEBDAV) null else ServidorDeMidia(conta, loja.segredo(conta))
    }
    // Sobe quando um vídeo é marcado como visto: a pasta se relê por baixo,
    // sem apagar a tela, e as barrinhas de onde parou se refazem.
    var recarga by remember { mutableIntStateOf(0) }
    val escopo = rememberCoroutineScope()
    // Biblioteca de filmes ou séries: ordem e filtros do servidor.
    var filtro by remember(atual) { mutableStateOf(ServidorDeMidia.Filtro()) }
    var generos by remember(atual) { mutableStateOf<List<String>>(emptyList()) }
    var mostrandoFiltro by remember { mutableStateOf(false) }
    val biblioteca = (atual as? Nivel.Item)?.item?.takeIf { it.tipoDeColecao == "movies" || it.tipoDeColecao == "tvshows" }
    LaunchedEffect(biblioteca?.id) {
        val b = biblioteca ?: return@LaunchedEffect
        generos = servidorDaConta?.let { runCatching { it.generos(b) }.getOrNull() }.orEmpty()
    }

    LaunchedEffect(atual, filtro) {
        conteudo = null
        erro = null
        // O começo de um Jellyfin ou Emby é a tela de faixas, que se carrega
        // sozinha; as páginas também.
        if (atual is Nivel.Bibliotecas || atual is Nivel.Video || atual is Nivel.Serie ||
            atual is Nivel.Pessoa || atual is Nivel.Canais
        ) return@LaunchedEffect
        runCatching { ler(conta, loja.segredo(conta), atual, filtro.takeIf { biblioteca != null }) }
            .onSuccess { conteudo = it }
            .onFailure {
                // Cancelada não é falha: trocou de pasta no meio da leitura.
                if (it is CancellationException) throw it
                erro = it.message ?: it.javaClass.simpleName
            }
    }

    // Voltar sobe um nível antes de sair da tela.
    val subir = { if (pilha.size > 1) pilha = pilha.dropLast(1) else onBack() }
    BackHandler(onBack = subir)

    Box(Modifier.fillMaxSize()) {
        FundoDeCapa(null)
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = margensSobADock(),
            topBar = {
                // Na TV a página do vídeo é a tela inteira, e na raiz o nome do
                // servidor já está na barra de cima: o título só repetiria.
                if (!(naTv && (atual is Nivel.Video || pilha.size == 1))) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(atual.nome, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (pilha.size > 1) {
                                    Text(
                                        pilha.drop(1).joinToString(" / ") { it.nome },
                                        color = LabTheme.faint, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = subir) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.voltar))
                            }
                        },
                        actions = {
                            if (biblioteca != null) {
                                IconButton(onClick = { mostrandoFiltro = true }) {
                                    Icon(
                                        if (filtro.ativo) Icons.Filled.FilterAlt else Icons.Filled.FilterList,
                                        contentDescription = stringResource(R.string.ft_filtros),
                                        tint = if (filtro.ativo) LabTheme.accent else LabTheme.muted,
                                    )
                                }
                            }
                            if (!naTv && (atual is Nivel.Pasta || atual is Nivel.Item)) {
                                IconButton(onClick = { mostrandoOpcoes = true }) {
                                    Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.exibicao))
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = LabTheme.text,
                            actionIconContentColor = LabTheme.muted,
                            navigationIconContentColor = LabTheme.muted,
                        ),
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                val pronto = conteudo
                when {
                    atual is Nivel.Bibliotecas -> InicioDoServidor(
                        conta,
                        onAbrir = { pilha = pilha + nivelDe(it) },
                        onCanais = { pilha = pilha + Nivel.Canais(contexto.getString(R.string.tv_ao_vivo)) },
                    )
                    atual is Nivel.Video && servidorDaConta != null -> PaginaDoVideo(
                        conta, servidorDaConta, atual.item,
                        onSerie = { id, nome -> pilha = pilha + Nivel.Serie(nome, id) },
                        onPessoa = { pilha = pilha + Nivel.Pessoa(it.nome, it) },
                        // Outro episódio da temporada troca de página, sem empilhar.
                        onVideo = { pilha = pilha.dropLast(1) + Nivel.Video(it.titulo, it) },
                        // A casinha da TV: o início do servidor, e não o do app.
                        onInicio = { pilha = listOf(Nivel.Bibliotecas(conta.nome)) },
                    )
                    atual is Nivel.Serie && servidorDaConta != null -> PaginaDaSerie(
                        conta, servidorDaConta, atual.id, atual.nome,
                        onEpisodio = { pilha = pilha + Nivel.Video(it.titulo, it) },
                        onPessoa = { pilha = pilha + Nivel.Pessoa(it.nome, it) },
                    )
                    atual is Nivel.Pessoa && servidorDaConta != null -> PaginaDaPessoa(servidorDaConta, atual.pessoa) {
                        pilha = pilha + nivelDe(it)
                    }
                    atual is Nivel.Canais && servidorDaConta != null -> PaginaDosCanais(conta, servidorDaConta)
                    erro != null -> Text(
                        erro!!,
                        color = LabTheme.muted, textAlign = TextAlign.Center, fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                    pronto == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else -> Pasta(
                        conteudo = pronto,
                        opcoes = opcoes,
                        naTv = naTv,
                        volta = recarga,
                        onAbrir = { pilha = pilha + it },
                        onVideo = { pilha = pilha + Nivel.Video(it.titulo, it) },
                        onOpcoes = { mostrandoOpcoes = true },
                        onMenu = { video -> pronto.doServidor[video.id]?.let { menuDe = it } },
                        servidor = servidorDaConta,
                        onMenuDaPasta = { menuDe = it },
                    )
                }
            }
        }
    }

    menuDe?.let { alvo ->
        val servidor = servidorDaConta ?: return@let
        MenuDoVisto(servidor, conta, alvo, onFechar = { menuDe = null }) {
            recarga++
            escopo.launch {
                runCatching { ler(conta, loja.segredo(conta), atual) }.onSuccess { conteudo = it }
            }
        }
    }

    if (mostrandoFiltro) {
        MenuDeFiltro(filtro, generos, onMudar = { filtro = it }, onFechar = { mostrandoFiltro = false })
    }

    if (mostrandoOpcoes) {
        val fechar = {
            mostrandoOpcoes = false
            opcoes.save()
        }
        if (naTv) {
            AlertDialog(
                onDismissRequest = fechar,
                containerColor = LabTheme.surface,
                text = { OpcoesDaBiblioteca(opcoes) },
                confirmButton = { TextButton(onClick = fechar) { Text(stringResource(R.string.fechar)) } },
            )
        } else {
            ModalBottomSheet(onDismissRequest = fechar, containerColor = LabTheme.surface) {
                OpcoesDaBiblioteca(opcoes)
            }
        }
    }
}

private suspend fun ler(conta: ContaDeRede, segredo: String?, nivel: Nivel, filtro: ServidorDeMidia.Filtro? = null): Conteudo = when (nivel) {
    is Nivel.Pasta -> {
        val entradas = WebDav(conta, segredo).listar(nivel.caminho)
        Conteudo(
            pastas = entradas.filter { it.ehPasta }
                .sortedWith { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.nome, b.nome) }
                .map { Nivel.Pasta(it.nome, it.caminho) to Icons.Filled.Folder },
            videos = entradas.filter { !it.ehPasta && MediaItem.isVideo(it.nome) }.map {
                val origem = MediaOrigin.WebDav(conta.id, it.caminho)
                MediaItem(
                    id = origem.resumeKey, title = it.nome, origin = origem,
                    fileSize = it.tamanho, modifiedAt = it.modificado,
                )
            },
        )
    }
    is Nivel.Bibliotecas -> Conteudo(
        pastas = ServidorDeMidia(conta, segredo).bibliotecas().map {
            Nivel.Item(it.nome, it) to iconeDaColecao(it.tipoDeColecao)
        },
        videos = emptyList(),
    )
    is Nivel.Video, is Nivel.Serie, is Nivel.Pessoa, is Nivel.Canais -> Conteudo(emptyList(), emptyList())
    is Nivel.Item -> {
        val servidor = ServidorDeMidia(conta, segredo)
        val itens = if (filtro != null) servidor.itens(nivel.item, filtro) else servidor.itens(nivel.item)
        Conteudo(
            ordemDoServidor = filtro != null,
            doServidor = itens.filter { it.ehVideo }.associateBy { MediaOrigin.Midia(conta.id, it.id).resumeKey },
            pastas = itens.filter { it.ehPasta }.map { nivelDe(it) to iconeDoTipo(it.tipo) },
            videos = itens.filter { it.ehVideo }.map {
                val origem = MediaOrigin.Midia(conta.id, it.id)
                MediaItem(
                    id = origem.resumeKey, title = it.titulo, origin = origem,
                    modifiedAt = it.criado, duration = it.duracao,
                )
            },
        )
    }
}

private fun iconeDaColecao(colecao: String?): ImageVector = when (colecao) {
    "movies" -> Icons.Filled.Movie
    "tvshows" -> Icons.Filled.Tv
    else -> Icons.Filled.Folder
}

private fun iconeDoTipo(tipo: String): ImageVector = when (tipo) {
    "Series" -> Icons.Filled.Tv
    "BoxSet" -> Icons.Filled.VideoLibrary
    else -> Icons.Filled.Folder
}

@Composable
private fun Pasta(
    conteudo: Conteudo,
    opcoes: LibraryOptions,
    naTv: Boolean,
    volta: Int,
    onAbrir: (Nivel) -> Unit,
    onVideo: (ServidorDeMidia.Item) -> Unit,
    onOpcoes: () -> Unit,
    onMenu: (MediaItem) -> Unit,
    servidor: ServidorDeMidia?,
    onMenuDaPasta: (ServidorDeMidia.Item) -> Unit,
) {
    val contexto = LocalContext.current
    // A fila de reprodução segue a ordem da tela: "próximo" vai para o vídeo
    // que está à frente. Episódio se chama "3. Nome", e a ordem por título é
    // natural — então a ordem padrão já é a da temporada.
    val videos = remember(conteudo, opcoes.sort, opcoes.ascending) {
        Snapshot.withoutReadObservation {
            val comDuracao = conteudo.videos.map { it.copy(duration = it.duration ?: Thumbnails.duracao(contexto, it)) }
            // Na biblioteca filtrada a ordem é a do servidor (nota, data, gênero).
            if (conteudo.ordemDoServidor) comDuracao else opcoes.sorted(comDuracao)
        }
    }
    /** Do Jellyfin e do Emby, o vídeo abre a página dele; do WebDAV, toca. */
    val tocarOuAbrir = { item: MediaItem ->
        conteudo.doServidor[item.id]?.let(onVideo) ?: Playback.start(contexto, item, videos)
    }

    if (conteudo.pastas.isEmpty() && videos.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.pasta_vazia), color = LabTheme.muted)
        }
        return
    }

    // Na TV a grade só rola quando o foco chega à borda, com folga para o
    // cartão aumentado: andar de lado numa fileira não mexe a tela.
    if (opcoes.layout == LibraryLayout.GRID) RolagemDeTvNasBordas(margem = 28.dp) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (naTv) 158.dp else Escala.capa()),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (naTv) {
                item(key = "opcoes", span = { GridItemSpan(maxLineSpan) }) { LinhaDeOpcoes(onOpcoes) }
            }
            items(
                conteudo.pastas, key = { it.first.hashCode() },
                span = { (nivel, _) -> if (servidor != null && doServidor(nivel) != null) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
            ) { (nivel, icone) ->
                val item = doServidor(nivel)
                if (servidor != null && item != null) {
                    Box(Modifier.padding(bottom = 8.dp)) {
                        CapaDePastaDoServidor(servidor, item, { onAbrir(nivel) }, { onMenuDaPasta(item) })
                    }
                } else {
                    LinhaDePasta(nivel.nome, icone) { onAbrir(nivel) }
                }
            }
            items(videos, key = { it.id }) { item ->
                Box(Modifier.padding(bottom = 8.dp)) {
                    CartaoDeVideo(item, volta, onOpcoes = menuDe(conteudo, item, onMenu)) { tocarOuAbrir(item) }
                }
            }
        }
    } else RolagemDeTvNasBordas(margem = 28.dp) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (naTv) {
                item(key = "opcoes") { Box(Modifier.padding(horizontal = 12.dp)) { LinhaDeOpcoes(onOpcoes) } }
            }
            items(conteudo.pastas, key = { it.first.hashCode() }) { (nivel, icone) ->
                Box(Modifier.padding(horizontal = 12.dp)) {
                    val item = doServidor(nivel)
                    if (servidor != null && item != null) {
                        LinhaDePastaDoServidor(servidor, item, { onAbrir(nivel) }, { onMenuDaPasta(item) })
                    } else {
                        LinhaDePasta(nivel.nome, icone) { onAbrir(nivel) }
                    }
                }
            }
            items(videos, key = { it.id }) { item ->
                LinhaDeVideo(item, volta, onOpcoes = menuDe(conteudo, item, onMenu)) { tocarOuAbrir(item) }
            }
        }
    }
}

/** A série, temporada ou coleção do servidor por trás da pasta — a que ganha capa. */
private fun doServidor(nivel: Nivel): ServidorDeMidia.Item? = when (nivel) {
    is Nivel.Item -> nivel.item
    is Nivel.Serie -> nivel.item
    else -> null
}

/** Só vídeo do Jellyfin ou do Emby tem menu; no WebDAV, segurar não faz nada. */
private fun menuDe(conteudo: Conteudo, item: MediaItem, onMenu: (MediaItem) -> Unit): (() -> Unit)? =
    if (item.id in conteudo.doServidor) ({ onMenu(item) }) else null

/** O atalho das opções na TV, onde o botão da barra de cima não é alcançável. */
@Composable
private fun LinhaDeOpcoes(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .tvFocus(LabTheme.radiusSmall, scale = 1.02f)
            .clip(RoundedCornerShape(LabTheme.radiusSmall))
            .background(LabTheme.glass, RoundedCornerShape(LabTheme.radiusSmall))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Tune, null, tint = LabTheme.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.exibicao), color = LabTheme.text, fontSize = 14.sp)
    }
}

@Composable
private fun LinhaDePasta(nome: String, icone: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .tvFocus(LabTheme.radiusCard, scale = 1.02f)
            .clip(RoundedCornerShape(LabTheme.radiusCard))
            .labCard()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(LabTheme.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icone, null, tint = LabTheme.accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            nome, color = LabTheme.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Icon(Icons.Filled.ChevronRight, null, tint = LabTheme.faint, modifier = Modifier.size(20.dp))
    }
}

/** Ordem, não vistos, favoritos e gênero — os filtros do app do Jellyfin. */
@Composable
private fun MenuDeFiltro(
    filtro: ServidorDeMidia.Filtro,
    generos: List<String>,
    onMudar: (ServidorDeMidia.Filtro) -> Unit,
    onFechar: () -> Unit,
) {
    MenuDoItem(stringResource(R.string.ft_filtros), onFechar) {
        val ordens = listOf(
            ServidorDeMidia.Filtro.Ordem.NOME to R.string.ft_nome,
            ServidorDeMidia.Filtro.Ordem.ADICIONADO to R.string.ft_adicionado,
            ServidorDeMidia.Filtro.Ordem.LANCAMENTO to R.string.ft_lancamento,
            ServidorDeMidia.Filtro.Ordem.NOTA to R.string.ft_nota,
            ServidorDeMidia.Filtro.Ordem.ALEATORIO to R.string.ft_aleatorio,
        )
        Text(stringResource(R.string.ft_ordenar), color = LabTheme.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        ordens.forEachIndexed { i, (ordem, nome) ->
            OpcaoDoMenu(stringResource(nome), icone = if (filtro.ordem == ordem) Icons.Filled.Check else null, primeira = i == 0) {
                onMudar(filtro.copy(ordem = ordem))
            }
        }
        OpcaoDoMenu(stringResource(R.string.ft_decrescente), marcado = filtro.decrescente) { onMudar(filtro.copy(decrescente = !filtro.decrescente)) }
        OpcaoDoMenu(stringResource(R.string.ft_nao_vistos), marcado = filtro.naoVistos) { onMudar(filtro.copy(naoVistos = !filtro.naoVistos)) }
        OpcaoDoMenu(stringResource(R.string.ft_favoritos), marcado = filtro.favoritos) { onMudar(filtro.copy(favoritos = !filtro.favoritos)) }
        if (generos.isNotEmpty()) {
            Text(stringResource(R.string.ft_genero), color = LabTheme.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            OpcaoDoMenu(stringResource(R.string.ft_todos_generos), icone = if (filtro.genero == null) Icons.Filled.Check else null) {
                onMudar(filtro.copy(genero = null))
            }
            generos.forEach { genero ->
                OpcaoDoMenu(genero, icone = if (filtro.genero == genero) Icons.Filled.Check else null) { onMudar(filtro.copy(genero = genero)) }
            }
        }
        if (filtro.ativo) {
            OpcaoDoMenu(stringResource(R.string.ft_limpar)) { onMudar(ServidorDeMidia.Filtro()) }
        }
    }
}
