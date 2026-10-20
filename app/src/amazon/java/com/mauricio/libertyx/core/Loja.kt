package com.mauricio.libertyx.core

import android.app.Activity
import android.content.Context
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.FulfillmentResult
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.Receipt
import com.amazon.device.iap.model.UserDataResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A conversa com a Amazon Appstore sobre a compra do Pro — a versão de Fire TV.
 *
 * Mesma cara pública da versão da Play (`src/play`), para o resto do app não saber de qual loja
 * veio: o tipo de build `amazon` troca um arquivo pelo outro. A Fire TV não tem Play, e a
 * Amazon exige o sistema de compras dela para liberar recurso dentro do app.
 *
 * Como na Play, sem servidor: o Pro é um "entitlement" (compra única) guardado na conta Amazon,
 * e o app pergunta a ela a cada abertura. Diferenças que este arquivo absorve:
 * - as respostas chegam todas num só ouvinte, e não em callbacks por pedido;
 * - a lista de compras vem em páginas (`hasMore`), e só a última diz "é tudo";
 * - reembolso não some da lista: o recibo volta marcado como cancelado;
 * - compra pendente é a de perfil infantil esperando o responsável aprovar.
 */
object Loja {

    /** O SKU cadastrado no console da Amazon: entitlement (compra única). Mesmo nome da Play. */
    const val PRODUTO = "libertyx_pro"

    sealed interface Situacao {
        data object Conectando : Situacao
        /** Sem a Appstore neste aparelho (emulador, APK instalado à mão). */
        data object Indisponivel : Situacao
        data class Pronta(val preco: String) : Situacao
    }

    private val _situacao = MutableStateFlow<Situacao>(Situacao.Conectando)
    val situacao: StateFlow<Situacao> = _situacao.asStateFlow()

    private val _pendente = MutableStateFlow(false)
    val pendente: StateFlow<Boolean> = _pendente.asStateFlow()

    private var iniciada = false
    private var conectada = false
    private var preco: String? = null
    /** Os recibos das páginas já recebidas de uma consulta que ainda tem mais. */
    private val acumulados = mutableListOf<Receipt>()

    fun iniciar(contexto: Context) {
        PurchasingService.registerListener(contexto.applicationContext, ouvinte)
        PurchasingService.enablePendingPurchases()
        iniciada = true
        conectar()
    }

    private fun conectar() {
        _situacao.value = Situacao.Conectando
        PurchasingService.getUserData()
    }

    /** Refaz a pergunta — ao abrir a tela do Pro e no "restaurar compra". */
    fun atualizar() {
        if (!iniciada) return
        if (!conectada) return conectar()
        consultarCompras()
        if (preco == null) PurchasingService.getProductData(setOf(PRODUTO))
    }

    private fun consultarCompras() {
        acumulados.clear()
        // true = desde o começo: um entitlement precisa da lista inteira para saber que não há compra.
        PurchasingService.getPurchaseUpdates(true)
    }

    /** A página do app na Amazon: o app da loja, se houver, senão o site. */
    fun paginaDoApp(pacote: String) = listOf(
        "amzn://apps/android?p=$pacote",
        "https://www.amazon.com/gp/mas/dl/android?p=$pacote",
    )

    /** @return false quando não há como comprar aqui (a tela oferece a página da loja). */
    @Suppress("UNUSED_PARAMETER")
    fun comprar(atividade: Activity): Boolean {
        if (_situacao.value !is Situacao.Pronta) return false
        PurchasingService.purchase(PRODUTO)
        return true
    }

    private fun processar(recibos: List<Receipt>, completa: Boolean) {
        val nossos = recibos.filter { it.sku == PRODUTO }
        val valido = nossos.firstOrNull { !it.isCanceled }
        if (valido != null) {
            _pendente.value = false
            Pro.registrarCompra(true)
            // Avisar a entrega é obrigatório e pode repetir sem custo: a Amazon ignora o repetido.
            PurchasingService.notifyFulfillment(valido.receiptId, FulfillmentResult.FULFILLED)
        } else if (completa) {
            // Lista inteira sem recibo válido: nunca comprou, ou foi reembolsado.
            Pro.registrarCompra(false)
        }
    }

    private val ouvinte = object : PurchasingListener {
        override fun onUserDataResponse(resposta: UserDataResponse) {
            if (resposta.requestStatus != UserDataResponse.RequestStatus.SUCCESSFUL) {
                _situacao.value = Situacao.Indisponivel
                return
            }
            conectada = true
            consultarCompras()
            PurchasingService.getProductData(setOf(PRODUTO))
        }

        override fun onProductDataResponse(resposta: ProductDataResponse) {
            val produto = resposta.productData?.get(PRODUTO)
            preco = produto?.price?.takeIf {
                resposta.requestStatus == ProductDataResponse.RequestStatus.SUCCESSFUL && it.isNotBlank()
            }
            _situacao.value = preco?.let { Situacao.Pronta(it) } ?: Situacao.Indisponivel
        }

        override fun onPurchaseUpdatesResponse(resposta: PurchaseUpdatesResponse) {
            // Só a resposta de sucesso conta: erro de rede não tira o Pro de quem pagou.
            if (resposta.requestStatus != PurchaseUpdatesResponse.RequestStatus.SUCCESSFUL) {
                acumulados.clear()
                return
            }
            acumulados += resposta.receipts.orEmpty()
            if (resposta.hasMore()) {
                PurchasingService.getPurchaseUpdates(false)
            } else {
                processar(acumulados.toList(), completa = true)
                acumulados.clear()
            }
        }

        override fun onPurchaseResponse(resposta: PurchaseResponse) {
            when (resposta.requestStatus) {
                PurchaseResponse.RequestStatus.SUCCESSFUL ->
                    resposta.receipt?.let { processar(listOf(it), completa = false) }
                // Já é dono (outro aparelho da mesma conta): a consulta traz o recibo.
                PurchaseResponse.RequestStatus.ALREADY_PURCHASED -> {
                    Pro.registrarCompra(true)
                    consultarCompras()
                }
                PurchaseResponse.RequestStatus.PENDING -> _pendente.value = true
                else -> Unit // Cancelou, falhou: a tela continua como estava.
            }
        }
    }
}
