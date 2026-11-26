import groovy.json.JsonSlurper
import java.util.Base64

/** Réglages Firebase publics d'une app Android, lus dans `google-services.json`. */
data class FirebaseOptionsValues(
    val applicationId: String,
    val apiKey: String,
    val projectId: String,
    val senderId: String,
)

/**
 * Lecture de `google-services.json` sans le plugin Google Services : le
 * fichier n'est jamais versionné, et sans lui l'app se compile quand même,
 * notifications coupées. Il vient du secret GitHub `GOOGLE_SERVICES_JSON`
 * (base64) ou de `android/app/google-services.json` sur un poste.
 */
object FirebaseConfig {
    /** Contenu du secret : le JSON lui-même ou son encodage base64. */
    fun decode(secret: String): String? {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) return trimmed
        return try {
            String(Base64.getMimeDecoder().decode(trimmed), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Réglages de l'app `packageName`, ou `null` si le fichier ne la déclare pas. */
    fun parse(json: String, packageName: String): FirebaseOptionsValues? {
        val root = try {
            JsonSlurper().parseText(json) as? Map<*, *>
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val project = root["project_info"] as? Map<*, *> ?: return null
        val projectId = project["project_id"] as? String ?: return null
        val senderId = project["project_number"]?.toString() ?: return null
        val clients = root["client"] as? List<*> ?: return null
        for (entry in clients) {
            val client = entry as? Map<*, *> ?: continue
            val info = client["client_info"] as? Map<*, *> ?: continue
            val android = info["android_client_info"] as? Map<*, *> ?: continue
            if (android["package_name"] != packageName) continue
            val appId = info["mobilesdk_app_id"] as? String ?: return null
            val apiKey = (client["api_key"] as? List<*>)
                ?.firstNotNullOfOrNull { (it as? Map<*, *>)?.get("current_key") as? String }
                ?: return null
            return FirebaseOptionsValues(appId, apiKey, projectId, senderId)
        }
        return null
    }
}
