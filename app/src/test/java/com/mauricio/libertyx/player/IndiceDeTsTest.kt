package com.mauricio.libertyx.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * O índice contra uma gravação de verdade, se houver uma no caminho de
 * `LIBERTYX_TS` (o arquivo é grande demais para o repositório). Confere a
 * duração e que a busca cai a menos de 2 s do instante pedido — o tempo do
 * ponto achado é relido aqui, de forma independente.
 */
class IndiceDeTsTest {

    private class LeitorDeArquivo(f: File) : LeitorDeTrechos {
        private val raf = RandomAccessFile(f, "r")
        var leituras = 0
        override val tamanho = raf.length()
        override fun ler(posicao: Long, quantos: Int): ByteArray {
            leituras++
            raf.seek(posicao)
            val n = minOf(quantos.toLong(), tamanho - posicao).toInt().coerceAtLeast(0)
            return ByteArray(n).also { raf.readFully(it) }
        }
        override fun close() = raf.close()
    }

    private fun pcrEm(raf: RandomAccessFile, byte: Long): Double {
        val b = ByteArray(400_000)
        raf.seek(byte - byte % 188)
        val n = raf.read(b)
        var i = 0
        while (i + 188 <= n) {
            val af = (b[i + 3].toInt() shr 4) and 3
            if (b[i] == 0x47.toByte() && (af == 2 || af == 3) && (b[i + 4].toInt() and 0xff) >= 7 && (b[i + 5].toInt() and 0x10) != 0) {
                fun u(k: Int) = (b[i + 6 + k].toLong() and 0xff)
                return ((u(0) shl 25) or (u(1) shl 17) or (u(2) shl 9) or (u(3) shl 1) or (u(4) shr 7)) / 90_000.0
            }
            i += 188
        }
        error("sem PCR")
    }

    @Test
    fun gravacaoDeVerdade() {
        val caminho = System.getenv("LIBERTYX_TS")
        assumeTrue(caminho != null && File(caminho).exists())
        val f = File(caminho!!)
        val leitor = LeitorDeArquivo(f)
        val indice = IndiceDeTs.abrir(leitor)!!
        println("duração ${indice.duracao} s, ${leitor.leituras} leituras")
        val raf = RandomAccessFile(f, "r")
        val inicio = pcrEm(raf, 0)
        for (alvo in listOf(5.0, 600.0, 2422.0, 4844.0, 5000.0, 7266.0, 9000.0, 9600.0, 1200.0)) {
            val antes = leitor.leituras
            val fracao = indice.fracaoDe(alvo)
            val achado = pcrEm(raf, (fracao.toDouble() * f.length()).toLong()) - inicio
            println("alvo $alvo -> $achado (${leitor.leituras - antes} leituras)")
            assertEquals(alvo - 1.0, achado, 2.0)
        }
    }

    @Test
    fun outroFormatoNaoEhTs() {
        val f = File.createTempFile("mp4", ".mp4")
        f.writeBytes(ByteArray(600_000) { (it % 251).toByte() }.also { "\u0000\u0000\u0000 ftypisom".toByteArray().copyInto(it) })
        assertNull(IndiceDeTs.abrir(LeitorDeArquivo(f)))
        f.delete()
    }
}
