package app.heywatchlist.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import app.heywatchlist.BuildConfig
import java.io.File

/**
 * Rapport du dernier arrêt brutal de l'app, montré au lancement suivant pour
 * que la personne puisse le copier : sans câble ni outil de développeur, c'est
 * la seule trace d'un plantage sur un vrai téléphone.
 *
 * Deux sources : l'exception Kotlin non rattrapée (pile complète, écrite au
 * moment du plantage) et, à défaut, la raison d'arrêt donnée par Android
 * (plantage natif, application figée : pile des fils pour ce dernier cas).
 * Rien n'est envoyé : le rapport reste sur le téléphone tant qu'on ne le copie pas.
 * Son contenu est technique (anglais, comme un journal), pas un texte d'interface.
 */
object CrashReport {
    private const val FILE = "last-crash.txt"
    private const val PREFERENCES = "diagnostics"
    private const val KEY_SEEN_EXIT = "seen_exit_timestamp"
    private const val MAX_CHARS = 16_000

    /** À appeler dans `Application.onCreate`, avant tout le reste. */
    fun install(context: Context) {
        val file = reportFile(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file.writeText(header() + "thread: ${thread.name}\n\n" + Log.getStackTraceString(error).take(MAX_CHARS))
            } catch (_: Exception) {
                // Le rapport est un bonus : il ne doit jamais masquer le plantage d'origine.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Rapport en attente, ou `null`. Lit le disque : hors du fil principal. */
    fun pending(context: Context): String? {
        val file = reportFile(context)
        if (file.exists()) return file.readText()
        val exit = lastUnseenExit(context) ?: return null
        val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR) {
            exit.traceInputStream?.use { it.bufferedReader().readText().take(MAX_CHARS) }
        } else {
            null
        }
        return buildString {
            append(header())
            append("exit: ").append(reasonLabel(exit.reason)).append('\n')
            exit.description?.let { append("description: ").append(it).append('\n') }
            trace?.let { append('\n').append(it) }
        }
    }

    /** Rapport lu et fermé : on ne le remontre plus. */
    fun dismiss(context: Context) {
        reportFile(context).delete()
        val latest = latestExit(context)?.timestamp ?: return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putLong(KEY_SEEN_EXIT, latest).apply()
    }

    private fun reportFile(context: Context) = File(context.noBackupFilesDir, FILE)

    private fun latestExit(context: Context): ApplicationExitInfo? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return manager.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
    }

    private fun lastUnseenExit(context: Context): ApplicationExitInfo? {
        val exit = latestExit(context) ?: return null
        if (exit.reason !in REPORTED_REASONS) return null
        val seen = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getLong(KEY_SEEN_EXIT, 0L)
        return exit.takeIf { it.timestamp > seen }
    }

    private val REPORTED_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
    )

    private fun reasonLabel(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        else -> reason.toString()
    }

    private fun header() =
        "HeyWatchlist ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n"
}
