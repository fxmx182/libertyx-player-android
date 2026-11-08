package com.mauricio.libertyx.core

/**
 * O áudio e a legenda escolhidos na página do vídeo, antes de tocar — como os
 * seletores do app do Jellyfin.
 *
 * A página conhece as faixas pelo que o servidor leu do arquivo; o VLC, pela
 * ordem em que as encontra. As duas listas seguem a ordem do arquivo, então a
 * escolha viaja como posição: "o segundo áudio", "a primeira legenda de
 * dentro". A legenda de fora (o `.srt` ao lado) viaja pelo número que o
 * servidor dá a ela, que vai no nome do arquivo baixado.
 *
 * Vale uma vez: o player consome a escolha ao abrir aquele vídeo.
 */
object EscolhaDeFaixas {

    sealed interface Legenda {
        /** Desligada de vez, sem nem a forçada. */
        data object Desligada : Legenda
        data class Interna(val posicao: Int) : Legenda
        data class Externa(val indice: Int) : Legenda
    }

    /** [legenda] `null`: a regra de sempre (só a forçada). */
    data class Pedido(val audio: Int? = null, val legenda: Legenda? = null)

    /** "Continuar" ou "Do início", escolhido na página: o player não pergunta de novo. */
    enum class Inicio { CONTINUAR, DO_COMECO }

    private val pedidos = mutableMapOf<String, Pedido>()
    private val inicios = mutableMapOf<String, Inicio>()

    @Synchronized
    fun pedir(pedido: Pedido, chave: String) {
        if (pedido.audio == null && pedido.legenda == null) pedidos.remove(chave) else pedidos[chave] = pedido
    }

    @Synchronized
    fun consumir(chave: String): Pedido? = pedidos.remove(chave)

    @Synchronized
    fun pedirInicio(inicio: Inicio, chave: String) { inicios[chave] = inicio }

    @Synchronized
    fun consumirInicio(chave: String): Inicio? = inicios.remove(chave)
}
