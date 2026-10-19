package com.mauricio.libertyx.core

import android.content.Context
import com.mauricio.libertyx.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * O LibertyX Pro: o que está liberado, e por quê.
 *
 * O app é grátis e completo como player de vídeo do aparelho. O Pro é uma
 * compra única que libera cinco coisas ([Recurso]); nos primeiros sete dias
 * elas vêm liberadas de graça, para quem instalou experimentar antes de pagar.
 *
 * **O teste é do app, não da Play.** A Play só oferece período grátis em
 * assinatura; para compra única não existe. Então o app anota o dia da
 * primeira abertura e conta a partir dele. A anotação vai no backup automático
 * do Android, o que faz reinstalar na mesma conta não recomeçar a contagem —
 * não é à prova de quem insiste, e não precisa ser: o preço é de um lanche.
 *
 * **Duas formas de comprar.** Na versão da Play, só pela Play (é a regra da loja). No APK do
 * GitHub ([BuildConfig.CHAVE_LIBERTYX]) também pela chave LibertyX, comprada com Bitcoin ou
 * USDT no bot do Telegram ([Chave]) — o que serve a quem não tem Play, como o Fire TV.
 *
 * **Terminado o teste, nada some.** Bloqueia só o que é Pro; a biblioteca, o
 * player, os gestos e a busca quadro a quadro continuam. Um app que se tranca
 * inteiro depois de uma semana converte mais e ganha avaliação de uma
 * estrela na mesma proporção.
 */
object Pro {

    /** O que o Pro libera. A ordem é a da tela de compra. */
    enum class Recurso { REDE, SEGUNDO_PLANO, JANELA, DORMIR, CAPTURA }

    sealed interface Estado {
        /** Comprado (pela Play ou com a chave LibertyX), ou o APK do dono ([BuildConfig.LIBERADO]). */
        data object Comprado : Estado
        data class Teste(val diasRestantes: Int) : Estado
        data object Expirado : Estado
    }

    val DIAS_DE_TESTE = BuildConfig.DIAS_DE_TESTE
    private const val DIA = 24L * 60 * 60 * 1000

    private const val KEY_INICIO = "pro.inicioDoTeste"
    private const val KEY_COMPRADO = "pro.comprado"
    private const val KEY_CHAVE = "pro.chave"

    private lateinit var app: Context
    private val _estado = MutableStateFlow<Estado>(Estado.Expirado)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    fun iniciar(contexto: Context) {
        app = contexto.applicationContext
        val prefs = Prefs.get(app)
        // A primeira abertura depois de instalar — ou de atualizar da versão
        // que ainda não tinha o Pro: quem já usava ganha a semana também.
        if (!prefs.contains(KEY_INICIO)) {
            prefs.edit().putLong(KEY_INICIO, System.currentTimeMillis()).apply()
        }
        recalcular()
    }

    /** Liberado agora, seja por compra, seja pelo teste. */
    val ativo: Boolean get() = estado.value !is Estado.Expirado

    /**
     * O que a Play respondeu sobre a compra.
     *
     * Guardado, e não só consultado: sem rede, ou num aparelho sem Play (Fire
     * TV, emulador), a Play não responde, e quem pagou não pode perder o Pro
     * por isso. Um reembolso chega do mesmo jeito — na próxima vez que ela
     * responder, com a lista vazia.
     */
    fun registrarCompra(comprado: Boolean) {
        Prefs.get(app).edit().putBoolean(KEY_COMPRADO, comprado).apply()
        recalcular()
    }

    /**
     * Ativa o Pro com a chave LibertyX. Guarda a chave inteira, e não um "ativado": a cada
     * abertura ela é conferida de novo, então editar o arquivo de preferências não libera nada.
     */
    fun ativarChave(texto: String): Chave.Resultado {
        val r = Chave.conferir(texto)
        if (r is Chave.Resultado.Valida && BuildConfig.CHAVE_LIBERTYX) {
            Prefs.get(app).edit().putString(KEY_CHAVE, r.texto).apply()
            recalcular()
        }
        return r
    }

    /** O número da licença ativada, para mostrar nas opções; null sem chave. */
    val licencaDaChave: String?
        get() = Prefs.get(app).getString(KEY_CHAVE, null)
            ?.let { (Chave.conferir(it) as? Chave.Resultado.Valida)?.id }

    private fun temChave(prefs: android.content.SharedPreferences): Boolean =
        BuildConfig.CHAVE_LIBERTYX && prefs.getString(KEY_CHAVE, null)
            ?.let { Chave.conferir(it) is Chave.Resultado.Valida } == true

    fun recalcular() {
        val prefs = Prefs.get(app)
        _estado.value = when {
            BuildConfig.LIBERADO || prefs.getBoolean(KEY_COMPRADO, false) || temChave(prefs) -> Estado.Comprado
            else -> {
                val inicio = prefs.getLong(KEY_INICIO, System.currentTimeMillis())
                // Relógio atrasado não vira dia negativo nem teste maior.
                val passados = ((System.currentTimeMillis() - inicio).coerceAtLeast(0) / DIA).toInt()
                val restam = DIAS_DE_TESTE - passados
                if (restam > 0) Estado.Teste(restam) else Estado.Expirado
            }
        }
    }
}
