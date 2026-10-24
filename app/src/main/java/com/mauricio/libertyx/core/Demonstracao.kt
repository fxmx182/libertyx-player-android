package com.mauricio.libertyx.core

import android.net.Uri

/**
 * Um filme para quem ainda não tem nenhum.
 *
 * Quem acaba de instalar numa Fire TV — e o revisor da loja — encontra o
 * aparelho sem vídeo algum e o servidor de casa ainda por conectar: sem isto,
 * o player não teria o que mostrar. Sintel é filme aberto da Blender (CC-BY),
 * em MKV com áudio AC-3 e legendas em dez idiomas, o que exercita de uma vez
 * o que o app sabe fazer. Toca direto do servidor da Blender, sem copiar nada.
 */
object Demonstracao {

    private const val URL = "https://download.blender.org/demo/movies/Sintel.2010.1080p.mkv"

    val item = MediaItem(
        id = "demo:sintel",
        title = "Sintel",
        origin = MediaOrigin.Remote(Uri.parse(URL)),
        duration = 888.0,
    )

    const val CREDITO = "Blender Foundation · CC-BY"
}
