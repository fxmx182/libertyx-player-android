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
            TextButton(enabled = !testando, onClick = onCancel) { Text(stringResource(R.string.cancelar)) }
        },
    )
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
}

private data class Conteudo(
    val pastas: List<Pair<Nivel, ImageVector>>,
    val videos: List<MediaItem>,
    /** Jellyfin e Emby: o item do servidor por trás de cada vídeo, para o "Já reproduzido". */
    val doServidor: Map<String, ServidorDeMidia.Item> = emptyMap(),
)

/**
 * Dentro de uma conta WebDAV, Jellyfin ou Emby — o mesmo desenho das pastas
 * SMB: grade ou lista, as mesmas opções, a mesma miniatura.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContaScreen(conta: ContaDeRede, onBack: () -> Unit) {
    val contexto = LocalContext.current
    val loja = remember { ContasDeRede.get(contexto) }
    val naTv = remember { Device.isTv(contexto) }
    val opcoes = remember { LibraryOptions(contexto) }
    var mostrandoOpcoes by remember { mutableStateOf(false) }

    val inicio: Nivel = remember(conta.id) {
        if (conta.tipo == ContaDeRede.Tipo.WEBDAV) Nivel.Pasta(conta.nome, WebDav(conta, null).raiz)
        else Nivel.Bibliotecas(conta.nome)
    }
    var pilha by remember(conta.id) { mutableStateOf(listOf(inicio)) }
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

    LaunchedEffect(atual) {
        conteudo = null
        erro = null
        // O começo de um Jellyfin ou Emby é a tela de faixas, que se carrega sozinha.
        if (atual is Nivel.Bibliotecas) return@LaunchedEffect
        runCatching { ler(conta, loja.segredo(conta), atual) }
            .onSuccess { conteudo = it }
            .onFailure { erro = it.message ?: it.javaClass.simpleName }
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
                        if (!naTv && atual !is Nivel.Bibliotecas) {
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
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                val pronto = conteudo
                when {
                    atual is Nivel.Bibliotecas -> InicioDoServidor(conta) { pilha = pilha + Nivel.Item(it.titulo, it) }
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

private suspend fun ler(conta: ContaDeRede, segredo: String?, nivel: Nivel): Conteudo = when (nivel) {
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
    is Nivel.Item -> {
        val itens = ServidorDeMidia(conta, segredo).itens(nivel.item)
        Conteudo(
            doServidor = itens.filter { it.ehVideo }.associateBy { MediaOrigin.Midia(conta.id, it.id).resumeKey },
            pastas = itens.filter { it.ehPasta }.map { Nivel.Item(it.titulo, it) to iconeDoTipo(it.tipo) },
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
            opcoes.sorted(conteudo.videos.map { it.copy(duration = it.duration ?: Thumbnails.duracao(contexto, it)) })
        }
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
                span = { (nivel, _) -> if (servidor != null && nivel is Nivel.Item) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
            ) { (nivel, icone) ->
                if (servidor != null && nivel is Nivel.Item) {
                    Box(Modifier.padding(bottom = 8.dp)) {
                        CapaDePastaDoServidor(servidor, nivel.item, { onAbrir(nivel) }, { onMenuDaPasta(nivel.item) })
                    }
                } else {
                    LinhaDePasta(nivel.nome, icone) { onAbrir(nivel) }
                }
            }
            items(videos, key = { it.id }) { item ->
                Box(Modifier.padding(bottom = 8.dp)) {
                    CartaoDeVideo(item, volta, onOpcoes = menuDe(conteudo, item, onMenu)) { Playback.start(contexto, item, videos) }
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
                    if (servidor != null && nivel is Nivel.Item) {
                        LinhaDePastaDoServidor(servidor, nivel.item, { onAbrir(nivel) }, { onMenuDaPasta(nivel.item) })
                    } else {
                        LinhaDePasta(nivel.nome, icone) { onAbrir(nivel) }
                    }
                }
            }
            items(videos, key = { it.id }) { item ->
                LinhaDeVideo(item, volta, onOpcoes = menuDe(conteudo, item, onMenu)) { Playback.start(contexto, item, videos) }
            }
        }
    }
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
