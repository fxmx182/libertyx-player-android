package com.mauricio.libertyx.player

import android.net.Uri
import android.util.Base64
import com.mauricio.libertyx.rede.ContasDeRede
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.TreeMap
import kotlin.math.abs

/** Um arquivo remoto lido por trechos soltos. */
interface LeitorDeTrechos : Closeable {
    val tamanho: Long

    /** Até [quantos] bytes a partir de [posicao]; menos só no fim do arquivo. */
    fun ler(posicao: Long, quantos: Int): ByteArray
}

/**
 * Um arquivo por HTTP (WebDAV, Jellyfin, Emby, link direto), com `Range`.
 *
 * Usuário e senha embutidos no endereço (o WebDAV entrega assim ao VLC) viram
 * o cabeçalho `Authorization`, que o OkHttp não monta sozinho.
 */
class LeitorHttp(endereco: Uri) : LeitorDeTrechos {
    private val url: String
    private val autorizacao: String?

    init {
        val info = endereco.encodedUserInfo
        if (info.isNullOrEmpty()) {
            url = endereco.toString()
            autorizacao = null
        } else {
            url = endereco.buildUpon().encodedAuthority(endereco.encodedAuthority!!.substringAfter('@')).build().toString()
            val par = Uri.decode(info.substringBefore(':')) + ":" + Uri.decode(info.substringAfter(':', ""))
            autorizacao = "Basic " + Base64.encodeToString(par.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun pedir(de: Long, ate: Long): Pair<ByteArray, Long?> {
        val pedido = Request.Builder().url(url).header("Range", "bytes=$de-$ate")
            .apply { autorizacao?.let { header("Authorization", it) } }
            .build()
        ContasDeRede.http.newCall(pedido).execute().use { r ->
            if (r.code != 206) throw IOException("HTTP ${r.code} sem Range")
            val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            return (r.body?.bytes() ?: ByteArray(0)) to total
        }
    }

    override val tamanho: Long by lazy {
        pedir(0, 0).second ?: throw IOException("HTTP sem tamanho")
    }

    override fun ler(posicao: Long, quantos: Int): ByteArray {
        if (posicao >= tamanho) return ByteArray(0)
        val ate = minOf(tamanho - 1, posicao + quantos - 1)
        return pedir(posicao, ate).first
    }

    override fun close() {}
}

/**
 * Duração e busca por tempo de uma gravação em MPEG-TS lida pela rede.
 *
 * Gravação de live costuma sair em MPEG-TS, às vezes com nome `.mp4`. TS não
 * tem índice nem duração no cabeçalho: o VLC só os descobre lendo o fim do
 * arquivo e procurando o tempo por busca binária — e no Android (VLC 3) ele só
 * faz isso quando a fonte é "rápida de pular", o que SMB e HTTP não são. A
 * duração ficava igual ao tempo já tocado, crescendo junto, e arrastar a barra
 * não levava a lugar nenhum.
 *
 * Aqui o app faz o que o VLC deixou de fazer: lê o relógio (PCR) do começo e
 * do fim para a duração, e para ir a um instante acha por busca binária o
 * byte onde ele está; o VLC então pula para essa posição do arquivo, o que ele
 * faz em qualquer fonte. Pular pela proporção de bytes não serve: o fluxo de
 * uma live varia muito — numa de 2h41 o meio do arquivo estava 5 minutos
 * antes do meio do tempo.
 */
class IndiceDeTs private constructor(
    private val leitor: LeitorDeTrechos,
    private val pacote: Int,
    private val deslocamento: Int,
    private val pid: Int,
    private val primeiro: Long,
    val duracao: Double,
) : Closeable {

    /** Pontos já medidos: byte do pacote → segundos desde o começo. */
    private val pontos = TreeMap<Long, Double>()

    init {
        pontos[0L] = 0.0
        pontos[leitor.tamanho] = duracao
    }

    /**
     * A fração do arquivo (0…1) onde fica [segundos]. Mira um pouco antes,
     * porque o VLC recomeça no quadro-chave seguinte ao byte pedido.
     */
    @Synchronized
    fun fracaoDe(segundos: Double): Float {
        val alvo = (segundos - 1.0).coerceIn(0.0, duracao)
        var baixo: Map.Entry<Long, Double> = pontos.entries.lastOrNull { it.value <= alvo } ?: pontos.firstEntry()!!
        var alto: Map.Entry<Long, Double> = pontos.entries.firstOrNull { it.value > alvo } ?: pontos.lastEntry()!!
        // Interpolação nos passos pares (acerta em poucos passos num fluxo
        // quase constante) e metade nos ímpares (garante que o vão encolha
        // mesmo quando o fluxo varia muito).
        for (passo in 0 until 64) {
            if (alvo - baixo.value < 0.5 || alto.value - baixo.value < 1.0) break
            val vao = alto.key - baixo.key
            if (vao < 4L * pacote) break
            val proporcao = if (passo % 2 == 0) {
                ((alvo - baixo.value) / (alto.value - baixo.value)).coerceIn(0.01, 0.99)
            } else 0.5
            val (byte, tempo) = pcrApos(baixo.key + (vao * proporcao).toLong(), alto.key) ?: break
            // Nenhum relógio entre as pontas: o ponto de baixo é o mais perto.
            if (byte >= alto.key || byte <= baixo.key) break
            pontos[byte] = tempo
            if (tempo <= alvo) baixo = java.util.AbstractMap.SimpleEntry(byte, tempo)
            else alto = java.util.AbstractMap.SimpleEntry(byte, tempo)
        }
        return fracao(baixo.key)
    }

    private fun fracao(byte: Long) = (byte.toDouble() / leitor.tamanho).toFloat()

    /** O primeiro PCR do [pid] a partir de [de], antes de [ate]: (byte, segundos). */
    private fun pcrApos(de: Long, ate: Long): Pair<Long, Double>? {
        var pos = alinhar(de)
        // O PCR vem a cada 100 ms no máximo: 64 KB bastam até ~5 Mb/s; acima
        // disso o bloco dobra até achar.
        var quanto = 64 * 1024
        while (pos < ate) {
            val bloco = leitor.ler(pos, quanto)
            quanto = minOf(quanto * 2, BLOCO * 4)
            if (bloco.size < pacote) return null
            var i = 0
            while (i + pacote <= bloco.size) {
                val pcr = pcrDoPacote(bloco, i + deslocamento, pid)
                if (pcr != null) return (pos + i) to segundos(pcr)
                i += pacote
            }
            pos += i
        }
        return null
    }

    /** O início de um pacote na altura de [byte], pelo alinhamento do arquivo. */
    private fun alinhar(byte: Long): Long = byte - byte % pacote

    private fun segundos(pcr: Long): Double = ((pcr - primeiro + VOLTA) % VOLTA) / 90_000.0

    override fun close() = leitor.close()

    companion object {
        private const val BLOCO = 256 * 1024
        private const val VOLTA = 1L shl 33

        /**
         * O índice, se o arquivo for MPEG-TS; `null` para qualquer outro
         * formato (lê só 4 KB). Bloqueia: chamar fora da tela.
         */
        fun abrir(leitor: LeitorDeTrechos): IndiceDeTs? {
            // 4 KB dizem se é TS; só então vale ler mais.
            val (pacote, deslocamento) = formato(leitor.ler(0, 4096)) ?: return null
            val inicio = leitor.ler(0, BLOCO)
            var pid = -1
            var primeiro = -1L
            var i = 0
            while (i + pacote <= inicio.size && pid < 0) {
                val p = pcrDoPacote(inicio, i + deslocamento, null)
                if (p != null) {
                    pid = pidDe(inicio, i + deslocamento)
                    primeiro = p
                }
                i += pacote
            }
            if (pid < 0) return null

            // O último PCR: blocos do fim para trás até achar um.
            val tamanho = leitor.tamanho
            var ultimo = -1L
            var fim = tamanho
            while (ultimo < 0 && fim > 0 && tamanho - fim < 16L * BLOCO) {
                val de = maxOf(0L, fim - BLOCO).let { it - it % pacote }
                val bloco = leitor.ler(de, (fim - de).toInt())
                var j = 0
                while (j + pacote <= bloco.size) {
                    pcrDoPacote(bloco, j + deslocamento, pid)?.let { ultimo = it }
                    j += pacote
                }
                fim = de
            }
            if (ultimo < 0) return null
            val duracao = ((ultimo - primeiro + VOLTA) % VOLTA) / 90_000.0
            if (duracao < 1) return null
            return IndiceDeTs(leitor, pacote, deslocamento, pid, primeiro, duracao)
        }

        /** Tamanho do pacote e onde o byte de sincronia fica: TS (188) ou M2TS (192, +4). */
        private fun formato(b: ByteArray): Pair<Int, Int>? {
            for ((pacote, desl) in listOf(188 to 0, 192 to 4)) {
                if (b.size < desl + pacote * 5) continue
                if ((0 until 5).all { b[desl + it * pacote] == 0x47.toByte() }) return pacote to desl
            }
            return null
        }

        private fun pidDe(b: ByteArray, i: Int): Int =
            ((b[i + 1].toInt() and 0x1f) shl 8) or (b[i + 2].toInt() and 0xff)

        /** A base de 33 bits do PCR do pacote em [i], se houver (e se for do [pid]). */
        private fun pcrDoPacote(b: ByteArray, i: Int, pid: Int?): Long? {
            if (i + 12 > b.size || b[i] != 0x47.toByte()) return null
            if (pid != null && pidDe(b, i) != pid) return null
            val campo = (b[i + 3].toInt() shr 4) and 3
            if (campo != 2 && campo != 3) return null
            if ((b[i + 4].toInt() and 0xff) < 7 || (b[i + 5].toInt() and 0x10) == 0) return null
            fun u(k: Int) = (b[i + 6 + k].toLong() and 0xff)
            return (u(0) shl 25) or (u(1) shl 17) or (u(2) shl 9) or (u(3) shl 1) or (u(4) shr 7)
        }
    }
}
