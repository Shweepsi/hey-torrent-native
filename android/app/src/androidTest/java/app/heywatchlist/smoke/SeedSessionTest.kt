package app.heywatchlist.smoke

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Test

/**
 * Prépare le test de démarrage sur émulateur : range une session factice, au
 * format de `KeystoreSessionStore`, pour que l'app démarre « connectée » sans
 * passer par Google. Le reste (lancer l'app, lire le journal) est fait par
 * `.github/scripts/android-smoke.sh`.
 *
 * Le format est recopié ici, sans référence aux classes de l'app : la release
 * testée est passée par R8, qui les renomme.
 */
class SeedSessionTest {
    @Test
    fun seedSignedInSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = """{"refresh_token":"smoke-test","user":{"id":"smoke","role":"admin","display_name":"Test","providers":["google"]}}"""
        context.getSharedPreferences(SESSION_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_VALUE, encrypt(session)).commit()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        // Mêmes valeurs que KeystoreSessionStore.
        const val SESSION_FILE = "session"
        const val ALIAS = "heywatchlist-session"
        const val KEY_VALUE = "value"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
