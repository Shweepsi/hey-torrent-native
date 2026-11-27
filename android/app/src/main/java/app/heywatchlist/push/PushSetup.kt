package app.heywatchlist.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.heywatchlist.BuildConfig
import app.heywatchlist.R
import app.heywatchlist.core.AccountFailureException
import app.heywatchlist.core.UniffiAccountGateway
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import uniffi.heywatchlist_core.PushPlatform

/**
 * Notifications « Disponible sur Plex » côté Android : Firebase, initialisé à
 * la main avec les réglages publics inscrits à la compilation (pas de plugin
 * Google Services), donne un jeton FCM que le cœur enregistre auprès du
 * serveur. Sans ces réglages, tout est simplement coupé.
 */
object PushSetup {
    /** Canal des notifications, identique à celui que vise le serveur. */
    const val CHANNEL_ID = "available"

    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotEmpty() && BuildConfig.FIREBASE_API_KEY.isNotEmpty()

    /** Au démarrage du processus : canal de notification et Firebase. */
    fun initialize(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.push_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.push_channel_description) }
        manager?.createNotificationChannel(channel)

        if (!isConfigured || FirebaseApp.getApps(context).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(context, options)
    }

    /** Jeton FCM de l'appareil, ou `null` (Firebase absent, pas de Play Services). */
    private suspend fun currentToken(): String? = suspendCancellableCoroutine { continuation ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
        } catch (_: Exception) {
            // Firebase non initialisé ou mal réglé : pas de notifications, sans planter.
            continuation.resume(null)
        }
    }

    /**
     * Enregistre ce téléphone pour la session en cours. Sans réseau ou sans
     * Firebase, on réessaiera au prochain démarrage ou au prochain jeton.
     */
    suspend fun register(gateway: UniffiAccountGateway, token: String? = null) {
        if (!isConfigured) return
        val value = token ?: currentToken() ?: return
        try {
            val account = gateway.account()
            gateway.call { account.registerPushDevice(PushPlatform.ANDROID, value) }
        } catch (_: AccountFailureException) {
            // Réessayé plus tard ; rien à montrer.
        }
    }
}
