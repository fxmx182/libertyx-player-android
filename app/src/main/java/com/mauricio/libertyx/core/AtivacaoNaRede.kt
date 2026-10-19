package com.mauricio.libertyx.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Ativar a chave da TV pelo celular.
 *
 * A chave tem mais de cem caracteres, e digitar isso no controle remoto é castigo. Então,
 * enquanto a tela da chave está aberta, a TV responde num endereço da rede de casa
 * (`http://192.168.x.x:8787`) com uma página de um campo só: no celular, na mesma rede, a
 * pessoa abre o endereço, cola a chave que o bot mandou no Telegram, e a TV ativa.
 *
 * Só HTTP na rede local e só enquanto a tela está aberta. Não há o que roubar: quem estiver na
 * mesma rede pode, no máximo, colar uma chave — e ela só ativa se for válida.
 */
class AtivacaoNaRede(
    private val textos: Textos,
    /** Chamado na thread do servidor com o que veio do celular; devolve o que dizer a ele. */
    private val aoReceber: (String) -> Chave.Resultado,
) {
    /** Os textos da página, já no idioma do aparelho. */
    data class Textos(
        val titulo: String,
        val explicacao: String,
        val botao: String,
        val ok: String,
        val erro: (Chave.Erro) -> String,
    )

    @Volatile private var servidor: ServerSocket? = null

    /** Liga o servidor e devolve o endereço para mostrar na tela, ou null sem rede local. */
    fun iniciar(): String? {
        val ip = ipLocal() ?: return null
        val s = runCatching { ServerSocket(PORTA) }.recoverCatching { ServerSocket(0) }.getOrNull() ?: return null
        servidor = s
        thread(name = "ativacao-na-rede", isDaemon = true) {
            while (!s.isClosed) {
                val cliente = try { s.accept() } catch (_: SocketException) { break }
                thread(isDaemon = true) { runCatching { atender(cliente) }; runCatching { cliente.close() } }
            }
        }
        return "http://${ip}:${s.localPort}"
    }

    fun parar() {
        runCatching { servidor?.close() }
        servidor = null
    }

    private fun atender(c: Socket) {
        c.soTimeout = 15_000
        val entrada = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
        val linha = entrada.readLine() ?: return
        var tamanho = 0
        while (true) {
            val h = entrada.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) tamanho = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val (mensagem, ok) = if (linha.startsWith("POST ") && tamanho in 1..4096) {
            val corpo = CharArray(tamanho).let { buf ->
                var lidos = 0
                while (lidos < tamanho) { val n = entrada.read(buf, lidos, tamanho - lidos); if (n < 0) break; lidos += n }
                String(buf, 0, lidos)
            }
            val chave = corpo.split('&').firstOrNull { it.startsWith("chave=") }
                ?.let { URLDecoder.decode(it.removePrefix("chave="), "UTF-8") }.orEmpty()
            when (val r = aoReceber(chave)) {
                is Chave.Resultado.Valida -> textos.ok to true
                is Chave.Resultado.Recusada -> textos.erro(r.erro) to false
            }
        } else null to false
        val html = pagina(mensagem, ok).toByteArray(Charsets.UTF_8)
        c.getOutputStream().run {
            write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            write(html)
            flush()
        }
    }

    private fun pagina(mensagem: String?, ok: Boolean): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val aviso = mensagem?.let {
            "<p class=\"${if (ok) "ok" else "erro"}\">${esc(it)}</p>"
        }.orEmpty()
        val formulario = if (ok) "" else """
            <form method="post">
              <textarea name="chave" rows="5" placeholder="LXP1-…" autocapitalize="characters" autocomplete="off" spellcheck="false" required></textarea>
              <button type="submit">${esc(textos.botao)}</button>
            </form>"""
        return """<!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${esc(textos.titulo)}</title>
            <style>
              body{margin:0;background:#0e0e10;color:#f0f0f0;font:16px/1.45 system-ui,-apple-system,sans-serif;padding:28px 18px}
              main{max-width:520px;margin:auto}
              h1{font-size:22px;margin:0 0 8px} h1 b{color:#fbc501}
              p{color:#c8c8d0;margin:0 0 18px}
              textarea{box-sizing:border-box;width:100%;background:#1c1c20;color:#fff;border:1px solid #3a3a40;border-radius:14px;padding:14px;font:15px ui-monospace,monospace;resize:vertical}
              button{width:100%;margin-top:14px;padding:16px;border:0;border-radius:14px;background:#fbc501;color:#000;font-weight:800;font-size:16px}
              .ok{color:#30d158;font-weight:700;font-size:18px} .erro{color:#ff453a;font-weight:700}
            </style></head><body><main>
            <h1>Liberty<b>X</b> Pro</h1>
            <p>${esc(textos.explicacao)}</p>
            $aviso
            $formulario
            </main></body></html>"""
    }

    companion object {
        private const val PORTA = 8787

        /** O IPv4 da rede de casa (Wi-Fi ou cabo), se houver. */
        fun ipLocal(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
