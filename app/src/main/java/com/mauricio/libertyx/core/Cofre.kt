package com.mauricio.libertyx.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException
import java.security.KeyStoreException

/**
 * O cofre das senhas e dos tokens dos servidores: preferências cifradas, com a
 * chave no Keystore do Android.
 *
 * A chave do Keystore não sai do aparelho — nem no backup. Por isso o cofre
 * fica fora do backup (ver `res/xml/regras_de_backup.xml`): antes ele voltava
 * numa reinstalação junto com a lista de servidores, sem a chave que o abre,
 * e o app caía calado num arquivo vazio. Os servidores apareciam, as senhas
 * não.
 *
 * Se mesmo assim o cofre não abrir por estar ilegível (veio de um backup
 * antigo, ou o Android perdeu a chave), ele é descartado e começa de novo,
 * cifrado — e quem abrir um servidor sem senha é chamado a digitá-la (ver
 * `ContaScreen` e `SmbBrowserScreen`). Só quando o próprio Keystore não
 * responde (ROM alternativa, usuário secundário) o app usa um arquivo
 * simples, para não ficar inútil por causa do modo de guardar a senha.
 */
object Cofre {

    private const val TAG = "LibertyX"

    fun abrir(context: Context, nome: String): SharedPreferences {
        val app = context.applicationContext
        val simples = app.getSharedPreferences("$nome.plain", Context.MODE_PRIVATE)
        val cofre = runCatching { cifrado(app, nome) }
            .recoverCatching { erro ->
                if (!ilegivel(erro)) throw erro
                Log.w(TAG, "cofre $nome ilegível ($erro): começa de novo, vazio")
                app.deleteSharedPreferences(nome)
                cifrado(app, nome)
            }
            .getOrElse { erro ->
                Log.w(TAG, "cofre $nome sem o Keystore ($erro): arquivo simples")
                return simples
            }
        trazerDoArquivoSimples(simples, cofre)
        return cofre
    }

    /**
     * As versões anteriores, ao não conseguir abrir o cofre, gravavam as
     * senhas digitadas de novo num arquivo simples. Elas passam para o cofre
     * cifrado — sem isso seriam perdidas outra vez nesta atualização — e o
     * arquivo simples fica vazio.
     */
    private fun trazerDoArquivoSimples(simples: SharedPreferences, cofre: SharedPreferences) {
        val antigas = simples.all.filterValues { it is String }
        if (antigas.isEmpty()) return
        cofre.edit().apply {
            antigas.forEach { (id, segredo) -> putString(id, segredo as String) }
        }.commit()
        simples.edit().clear().commit()
    }

    private fun cifrado(app: Context, nome: String): SharedPreferences {
        val chave = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, nome, chave,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * O arquivo existe mas não decifra: tag que não bate, conjunto de chaves
     * corrompido. O Keystore fora do ar é outra coisa — esse passa, e o
     * arquivo fica intacto para quando ele voltar.
     */
    private fun ilegivel(erro: Throwable): Boolean = generateSequence(erro) { it.cause }.any {
        (it is GeneralSecurityException && it !is KeyStoreException) ||
            it.javaClass.simpleName == "InvalidProtocolBufferException"
    }
}
