package app.heywatchlist.push

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Notification
import android.app.NotificationManager
import androidx.annotation.StringRes
import app.heywatchlist.MainActivity
import app.heywatchlist.R
import app.heywatchlist.appServices
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reçoit les messages FCM. App en arrière-plan : le système affiche lui-même
 * la notification (texte traduit par `body_loc_key`). App au premier plan :
 * on l'affiche ici avec les mêmes ressources.
 */
class HeyMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { PushSetup.register(appServices.account, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        val body = bodyText(notification.bodyLocalizationKey, notification.bodyLocalizationArgs.orEmpty())
            ?: return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val built = Notification.Builder(this, PushSetup.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        val tag = notification.tag ?: message.collapseKey
        getSystemService(NotificationManager::class.java)?.notify(tag, 0, built)
    }

    /** Corps traduit d'une clé envoyée par le serveur ; `null` si la clé est inconnue. */
    private fun bodyText(key: String?, args: Array<out String>): String? {
        @StringRes val res = when (key) {
            "push_available_movie" -> R.string.push_available_movie
            "push_available_show" -> R.string.push_available_show
            "push_available_season" -> R.string.push_available_season
            "push_available_episode" -> R.string.push_available_episode
            "push_test" -> R.string.push_test
            else -> return null
        }
        return getString(res, *args)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
