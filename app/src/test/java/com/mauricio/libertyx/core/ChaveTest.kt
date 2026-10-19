package com.mauricio.libertyx.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * As chaves daqui foram emitidas pelo emissor do bot (lib/licenca.js) com uma chave privada
 * descartável; a pública dela entra no lugar da verdadeira só durante o teste.
 */
class ChaveTest {
    private val publica = "3059301306072a8648ce3d020106082a8648ce3d0301070342000452979e13142a74f11133d5858cd8b42c0bbd01f6d82a2e34d925ad56c40190a4cba0fc1ef71a339f859dbe7c4757b21206514840c4819cd2dfb12523b06c1cd8"
    private val player = "LXP1-041028-V6WKAQ-N38GA8-RN42P7-FT9BFV-WGSKTN-SMYC9G-WQR7V1-RA2HBW-6RQD3M-JM9ATJ-9G4P4Q-MHXPWN-W90QJ9-J2FM56-71JJQT-EXHE0P-W8G3ZD-ZK6JHX-E97Z85-K8"
    private val pacote = "LXP1-044G28-XY0FQB-50RHBG-C3EJXF-HKDQ9D-WTDBCZ-PADC4X-VTDJZ2-XGRQQR-G8RSHN-RWEV49-5QGEMX-3DJMTF-57GTRQ-2JSN1Y-DV0DXD-RC9ZH2-J2NSEX-7KRNA5-590V4H-BR"
    private val partition = "LXP1-040G28-TMYV6E-XQ803A-16BGPM-CBX14W-92G5T8-A4QBKQ-TEQC7S-3D05BB-M8JVWS-6338JQ-QY6BT9-HPD46R-095FK0-HHMEWG-MTCR32-7H970N-WGCJ2K-YWESW6-8DCR6H-7G"
    private val folder = "LXP1-041G28-XP4WTR-V2Z0FW-CQG2CQ-CY0VMD-92RRGA-7T60S2-964EZN-KMSM6G-GG4P8Q-NMH471-2238JS-E2YYZC-VBGCTV-2YFE7E-CS6Y2D-0AHPTV-P8X5NF-0F2WXZ-9CABZN-BR"
    private val desconhecido = "LXP1-043G28-STYMGA-D4R2JY-9SJ2XJ-BHD3YJ-C4Y28T-GJ5T67-1C6EDG-C4TWVM-VZKN28-9EBTXG-H8J2W4-DBK28Y-859J6Y-369D99-V37Y2N-QJQ9HE-GW5ZNJ-M4CA0E-86JQ7C-SM"

    @Before fun usarPublicaDeTeste() { Chave.publicaDeTeste = Chave.hex(publica) }
    @After fun voltarPublica() { Chave.publicaDeTeste = null }

    @Test fun chaveDoPlayerVale() {
        assertTrue(Chave.conferir(player) is Chave.Resultado.Valida)
    }

    @Test fun chaveDoPacoteVale() {
        assertTrue(Chave.conferir(pacote) is Chave.Resultado.Valida)
    }

    @Test fun chaveDeOutroAppRecusadaComMotivo() {
        assertEquals(Chave.Resultado.Recusada(Chave.Erro.OUTRO_APP), Chave.conferir(partition))
        assertEquals(Chave.Resultado.Recusada(Chave.Erro.OUTRO_APP), Chave.conferir(folder))
    }

    @Test fun produtoDesconhecidoPedeAtualizar() {
        assertEquals(Chave.Resultado.Recusada(Chave.Erro.VERSAO), Chave.conferir(desconhecido))
    }

    @Test fun digitacaoToleranteNoCelularENaTv() {
        // minúsculas, espaços no lugar dos traços e O no lugar de zero
        val digitada = player.lowercase().replace('-', ' ').replace('0', 'o')
        assertTrue(Chave.conferir(digitada) is Chave.Resultado.Valida)
        assertTrue(Chave.conferir("  $player\n") is Chave.Resultado.Valida)
    }

    @Test fun adulteradaOuCortadaRecusada() {
        val c = player[20]
        val trocada = player.substring(0, 20) + (if (c == 'A') 'B' else 'A') + player.substring(21)
        assertEquals(Chave.Resultado.Recusada(Chave.Erro.INVALIDA), Chave.conferir(trocada))
        assertEquals(Chave.Resultado.Recusada(Chave.Erro.FORMATO), Chave.conferir(player.substring(0, 40)))
        assertEquals(Chave.Resultado.Recusada(Chave.Erro.FORMATO), Chave.conferir("isto não é chave"))
    }

    /** Com uma chave de verdade em LXP_CHAVE_REAL, confere que a pública embutida é a do emissor. */
    @Test fun chaveRealConfereComAPublicaEmbutida() {
        val real = System.getenv("LXP_CHAVE_REAL") ?: return
        Chave.publicaDeTeste = null
        assertTrue(Chave.conferir(real) is Chave.Resultado.Valida)
    }

    @Test fun naChavePublicaVerdadeiraAsDeTesteNaoValem() {
        Chave.publicaDeTeste = null
        assertEquals(Chave.Resultado.Recusada(Chave.Erro.INVALIDA), Chave.conferir(player))
    }
}
