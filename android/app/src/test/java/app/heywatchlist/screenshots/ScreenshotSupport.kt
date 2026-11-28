package app.heywatchlist.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.heywatchlist.ui.components.AppBackdrop
import app.heywatchlist.ui.theme.HeyTheme
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.math.abs

/**
 * Outils des captures d'écran : images factices (aucun appel réseau, aucune
 * affiche réelle dans le dépôt), décor de l'app et écriture des PNG.
 */
object Screenshots {
    /** Qualificatifs Robolectric : un téléphone courant, 412 × 915 dp. */
    const val PHONE = "w412dp-h915dp-xxhdpi"

    private val dir: File
        get() = File(System.getProperty("heywatchlist.captures.dir") ?: "build/captures")

    /** Remplace Coil par un chargeur qui dessine une image factice pour chaque adresse `fake://`. */
    fun installFakeImages() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val loader = ImageLoader.Builder(context)
            .components { add(FakeImages) }
            .coroutineContext(Dispatchers.Unconfined)
            .build()
        SingletonImageLoader.setUnsafe(loader)
    }

    /** Pose [content] sur le décor de l'app, laisse finir les fondus, joue [act], puis écrit `<name>.png`. */
    fun capture(
        rule: ComposeContentTestRule,
        name: String,
        dark: Boolean = false,
        act: ComposeContentTestRule.() -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            HeyTheme(darkTheme = dark) {
                AppBackdrop { content() }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(FADE_WAIT_MILLIS)
        rule.waitForIdle()
        // Un geste avant la capture (défilement, pour voir les barres flottantes sur le contenu).
        rule.act()
        rule.mainClock.advanceTimeBy(FADE_WAIT_MILLIS)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(dir, "$name.png").absolutePath)
    }

    private const val FADE_WAIT_MILLIS = 1_500L
}

/** Affiche factice : un dégradé dont les teintes dépendent de l'adresse. */
private object FakeImages : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val key = chain.request.data.toString()
        val wide = "backdrop" in key || "still" in key
        val bitmap = if (wide) drawImage(key, 780, 440) else drawImage(key, 300, 450)
        return SuccessResult(image = bitmap.asImage(), request = chain.request, dataSource = DataSource.MEMORY)
    }

    private fun drawImage(key: String, width: Int, height: Int): Bitmap {
        val hue = (abs(key.hashCode()) % 360).toFloat()
        val top = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.75f))
        val bottom = android.graphics.Color.HSVToColor(floatArrayOf((hue + 50f) % 360f, 0.8f, 0.25f))
        val glow = android.graphics.Color.HSVToColor(floatArrayOf((hue + 180f) % 360f, 0.5f, 1f))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, width * 0.3f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = RadialGradient(
            width * 0.7f, height * 0.35f, width * 0.55f,
            glow, android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = android.graphics.Color.argb(90, 0, 0, 0)
        canvas.drawCircle(width * 0.32f, height * 0.62f, width * 0.22f, paint)
        return bitmap
    }
}
