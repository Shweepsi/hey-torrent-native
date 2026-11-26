package app.heywatchlist.push

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.heywatchlist.appServices

/**
 * Une fois connecté : demande la permission d'afficher des notifications
 * (Android 13 et plus), puis enregistre le téléphone auprès du serveur.
 * Le jeton est enregistré même si la permission est refusée : l'autoriser plus
 * tard dans les réglages du système suffit alors.
 */
@Composable
fun PushRegistrationEffect() {
    val context = LocalContext.current
    val services = context.appServices
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!PushSetup.isConfigured) return@LaunchedEffect
        val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsking) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        PushSetup.register(services.account)
    }
}
