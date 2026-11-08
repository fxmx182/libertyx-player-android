package com.mauricio.libertyx.rede

import android.content.Context
import com.mauricio.libertyx.core.Historico
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.resumeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Conta ao Jellyfin ou ao Emby o que o player está tocando.
 *
 * É o que põe o filme pela metade no "Continuar assistindo" do servidor e
 * marca o episódio como visto no app dele, na TV e no navegador. Para os
 * outros vídeos do app, não faz nada.
 *
 * Os avisos saem um de cada vez e na ordem: um "parou" que chegasse antes do
 * "começou" deixaria o servidor achando que o filme ainda está tocando. A
 * fila é uma só para o app inteiro: a tela do servidor espera por ela
 * ([emDia]) antes de pedir o "Continuar".
 */
class AvisoAoServidor(context: Context) {

    /** [convertendo]: o servidor converte ou reempacota — o [id] é o que já foi no endereço do vídeo. */
    private class Sessao(
        val conta: ContaDeRede, val servidor: ServidorDeMidia, val itemId: String, val id: String,
        val convertendo: Boolean,
    )

    private val contexto = context.applicationContext

    private var sessao: Sessao? = null
    private var ultimoAviso = 0L
    private var pausado = false

    fun comecou(item: MediaItem, segundos: Double) {
        parou(segundos)
        val origem = item.origin as? MediaOrigin.Midia ?: return
        val loja = ContasDeRede.get(contexto)
        val conta = loja.byId(origem.contaId) ?: return
        val conversao = Conversoes.sessao(origem.itemId)
        val nova = Sessao(
            conta, ServidorDeMidia(conta, loja.segredo(conta)), origem.itemId,
            conversao ?: UUID.randomUUID().toString(), convertendo = conversao != null,
        )
        sessao = nova
        pausado = false
        ultimoAviso = System.currentTimeMillis()
        avisar(nova, null, segundos)
    }

    /**
     * Chamado a cada atualização de tempo, mas só avisa a cada dez segundos —
     * ou na hora, quando pausa ou volta a tocar.
     */
    fun andou(segundos: Double, pausado: Boolean) {
        val atual = sessao ?: return
        val agora = System.currentTimeMillis()
        if (pausado == this.pausado && agora - ultimoAviso < INTERVALO) return
        this.pausado = pausado
        ultimoAviso = agora
        avisar(atual, "Progress", segundos)
    }

    /**
     * Pode ser chamado mais de uma vez: só o primeiro chega ao servidor.
     *
     * [visto]: assistido até o fim. Depois que o servidor marca o episódio
     * como visto, o "A seguir" dele já aponta o próximo — e é esse que entra
     * no histórico, no lugar do que terminou.
     */
    fun parou(segundos: Double, visto: Boolean = false) {
        val atual = sessao ?: return
        sessao = null
        avisar(atual, "Stopped", segundos)
        if (atual.convertendo) {
            // Sem isto o ffmpeg do servidor seguiria convertendo, e o
            // sintonizador ficaria preso num canal que ninguém vê.
            val transmissao = Conversoes.transmissao(atual.itemId)
            Conversoes.encerrar(atual.itemId)
            ultimo = fila.launch {
                atual.servidor.pararConversao(atual.id)
                transmissao?.let { atual.servidor.fecharCanal(it) }
            }
        }
        if (!visto) return
        ultimo = fila.launch {
            val proximo = runCatching { atual.servidor.proximoEpisodio(atual.itemId) }.getOrNull() ?: return@launch
            val terminado = MediaOrigin.Midia(atual.conta.id, atual.itemId).resumeKey
            withContext(Dispatchers.Main) {
                Historico.get(contexto).terminou(terminado, paraTocar(atual.conta, proximo))
            }
        }
    }

    private fun avisar(sessao: Sessao, evento: String?, segundos: Double) {
        val pausadoAgora = pausado
        ultimo = fila.launch {
            // Sem rede, o aviso se perde, e o vídeo segue tocando: a marca do
            // aparelho continua valendo, e o próximo aviso corrige o servidor.
            runCatching {
                sessao.servidor.informar(evento, sessao.itemId, segundos, pausadoAgora, sessao.id, sessao.convertendo)
            }
        }
    }

    companion object {
        private const val INTERVALO = 10_000L

        @OptIn(ExperimentalCoroutinesApi::class)
        private val fila = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        /** O último aviso posto na fila: com ela em série, esperar por ele é esperar por todos. */
        @Volatile private var ultimo: Job? = null

        /** Espera os avisos a caminho do servidor — no máximo uns segundos, para a tela não travar sem rede. */
        suspend fun emDia() {
            withTimeoutOrNull(4_000) { ultimo?.join() }
        }
    }
}
