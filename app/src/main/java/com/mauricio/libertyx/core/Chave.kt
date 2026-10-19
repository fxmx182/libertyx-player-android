package com.mauricio.libertyx.core

import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * A chave LibertyX comprada pelo Telegram (bot `@libertx_partition_bot`, que vende todos os
 * apps): "LXP1-" + base32 Crockford de 12 bytes de dados e 64 de assinatura ECDSA P-256.
 *
 * Os dados: versão (1), produto (1 Partition, 2 Player, 3 Folder, 9 pacote com todos), dias
 * desde 2026-01-01 e o número da licença. O app só tem a chave pública e confere sozinho, sem
 * internet — o mesmo formato do LibertyX Partition (`Core/Licensing/License.cs`), e a mesma
 * chave pública.
 */
object Chave {

    /** O produto deste app; o pacote ([PACOTE]) também vale. */
    const val PRODUTO = 2
    private const val PACOTE = 9

    private const val PREFIXO = "LXP1"
    // A pública em SubjectPublicKeyInfo (DER), em hexadecimal: sem Base64, que no Android
    // antigo só existe na versão do android.util, que os testes de JVM não têm.
    private const val PUBLICA =
        "3059301306072a8648ce3d020106082a8648ce3d030107034200041a9bd4b8" +
        "1a7c8fce09fff6a52ed05fadc56b3856dfd038433887339dd045c02b1a3884" +
        "7aa8129f5d6a5336e5a805d5c8597cda3e6a53eb443cd135e050246c47"
    /** Só para os testes: troca a pública por uma de teste (as chaves dos testes não valem no app). */
    internal var publicaDeTeste: ByteArray? = null
    private const val ALFABETO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford: sem I, L, O, U

    /** O motivo de uma chave não valer, para a tela dizer o que fazer. */
    enum class Erro { FORMATO, INVALIDA, OUTRO_APP, VERSAO }

    sealed interface Resultado {
        /** [id]: o número da licença, em hexadecimal, como no registro do bot. */
        data class Valida(val id: String, val texto: String) : Resultado
        data class Recusada(val erro: Erro) : Resultado
    }

    fun conferir(texto: String): Resultado {
        val s = normalizar(texto)
        if (!s.startsWith(PREFIXO)) return Resultado.Recusada(Erro.FORMATO)
        val bruto = decodificar(s.substring(PREFIXO.length)) ?: return Resultado.Recusada(Erro.FORMATO)
        if (bruto.size != 76) return Resultado.Recusada(Erro.FORMATO)
        val dados = bruto.copyOfRange(0, 12)
        val assinatura = bruto.copyOfRange(12, 76)
        val ok = runCatching {
            val chave = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicaDeTeste ?: hex(PUBLICA)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(chave)
                update(dados)
                verify(der(assinatura))
            }
        }.getOrDefault(false)
        if (!ok) return Resultado.Recusada(Erro.INVALIDA)
        val produto = dados[1].toInt() and 0xFF
        if (dados[0].toInt() != 1) return Resultado.Recusada(Erro.VERSAO)
        if (produto in setOf(1, 2, 3) && produto != PRODUTO) return Resultado.Recusada(Erro.OUTRO_APP)
        if (produto != PRODUTO && produto != PACOTE) return Resultado.Recusada(Erro.VERSAO)
        val id = dados.copyOfRange(4, 12).joinToString("") { "%02X".format(it) }
        return Resultado.Valida(id, s)
    }

    /**
     * Maiúsculas, sem traços nem espaços, e as letras que o Crockford não usa trocadas pelo
     * algarismo parecido (O por 0; I e L por 1) — quem digita a chave do celular para a TV
     * erra exatamente aí. O prefixo tem um L de verdade: desfaz a troca só nele.
     */
    private fun normalizar(t: String): String {
        val s = buildString {
            for (c in t.uppercase()) {
                when (c) {
                    '-', ' ', '\t', '\r', '\n' -> Unit
                    'O' -> append('0')
                    'I', 'L' -> append('1')
                    else -> append(c)
                }
            }
        }
        return if (s.startsWith("1XP1")) PREFIXO + s.substring(4) else s
    }

    private fun decodificar(s: String): ByteArray? {
        val saida = ArrayList<Byte>(80)
        var acc = 0
        var bits = 0
        for (c in s) {
            val v = ALFABETO.indexOf(c)
            if (v < 0) return null
            acc = (acc shl 5) or v
            bits += 5
            if (bits >= 8) {
                saida.add(((acc shr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
            acc = acc and ((1 shl bits) - 1)
        }
        return saida.toByteArray()
    }

    internal fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** r‖s (64 bytes, como o bot e o C# assinam) → DER, que é o que o Java confere. */
    private fun der(rs: ByteArray): ByteArray {
        fun inteiro(b: ByteArray): ByteArray {
            val v = BigInteger(1, b).toByteArray() // já com o 0x00 na frente se o bit alto estiver ligado
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val corpo = inteiro(rs.copyOfRange(0, 32)) + inteiro(rs.copyOfRange(32, 64))
        return byteArrayOf(0x30, corpo.size.toByte()) + corpo
    }
}
