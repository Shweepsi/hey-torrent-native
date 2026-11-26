package app.heywatchlist.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import uniffi.heywatchlist_core.SessionStore

/**
 * Coffre de la session : la valeur du cœur est chiffrée en AES-256-GCM avec une
 * clé du Keystore Android (jamais exportable), puis rangée dans des
 * préférences privées exclues de la sauvegarde (`allowBackup="false"`).
 *
 * Toute erreur (clé perdue après une restauration, valeur abîmée) vaut
 * « aucune session » : la personne se reconnecte, rien ne plante.
 *
 * [file] et [alias] séparent plusieurs coffres (fichier et clé propres à chacun).
 */
class KeystoreSessionStore(
    context: Context,
    file: String = SESSION_FILE,
    private val alias: String = SESSION_ALIAS,
) : SessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    override fun load(): String? = try {
        preferences.getString(KEY_VALUE, null)?.let(::decrypt)
    } catch (error: Exception) {
        clear()
        null
    }

    override fun save(value: String) {
        try {
            preferences.edit().putString(KEY_VALUE, encrypt(value)).apply()
        } catch (error: Exception) {
            clear()
        }
    }

    override fun clear() {
        preferences.edit().remove(KEY_VALUE).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        require(sealed.size > IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_LENGTH))
        return String(cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH), Charsets.UTF_8)
    }

    private companion object {
        const val SESSION_FILE = "session"
        const val SESSION_ALIAS = "heywatchlist-session"
        const val KEY_VALUE = "value"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
