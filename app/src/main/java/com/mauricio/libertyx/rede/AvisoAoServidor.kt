package com.mauricio.libertyx.rede

import android.content.Context
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.MediaOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Conta ao Jellyfin ou ao Emby o que o player está tocando.
 *
 * É o que põe o filme pela metade no "Continuar assistindo" do servidor e
 * marca o episódio como visto no app dele, na TV e no navegador. Para os
 * outros vídeos do app, não faz nada.
 *
 * Os avisos saem um de cada vez e na ordem: um "parou" que chegasse antes do
 * "começou" deixaria o servidor achando que o filme ainda está tocando.
 */
class AvisoAoServidor(context: Context) {

    private class Sessao(val servidor: ServidorDeMidia, val itemId: String, val id: String)

    private val contexto = context.applicationContext

    @OptIn(ExperimentalCoroutinesApi::class)
    private val fila = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var sessao: Sessao? = null
    private var ultimoAviso = 0L
    private var pausado = false

    fun comecou(item: MediaItem, segundos: Double) {
        parou(segundos)
        val origem = item.origin as? MediaOrigin.Midia ?: return
        val loja = ContasDeRede.get(contexto)
        val conta = loja.byId(origem.contaId) ?: return
        val nova = Sessao(ServidorDeMidia(conta, loja.segredo(conta)), origem.itemId, UUID.randomUUID().toString())
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

    /** Pode ser chamado mais de uma vez: só o primeiro chega ao servidor. */
    fun parou(segundos: Double) {
        val atual = sessao ?: return
        sessao = null
        avisar(atual, "Stopped", segundos)
    }

    private fun avisar(sessao: Sessao, evento: String?, segundos: Double) {
        val pausadoAgora = pausado
        fila.launch {
            // Sem rede, o aviso se perde, e o vídeo segue tocando: a marca do
            // aparelho continua valendo, e o próximo aviso corrige o servidor.
            runCatching { sessao.servidor.informar(evento, sessao.itemId, segundos, pausadoAgora, sessao.id) }
        }
    }

    private companion object {
        const val INTERVALO = 10_000L
    }
}
