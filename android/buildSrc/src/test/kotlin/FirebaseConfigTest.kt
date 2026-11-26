import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lecture de `google-services.json` (valeurs inventées). */
class FirebaseConfigTest {
    private val json = """
        {
          "project_info": { "project_number": "123456789", "project_id": "projet-test" },
          "client": [
            {
              "client_info": {
                "mobilesdk_app_id": "1:123456789:android:aaaa",
                "android_client_info": { "package_name": "app.exemple" }
              },
              "api_key": [ { "current_key": "cle-publique-a" } ]
            },
            {
              "client_info": {
                "mobilesdk_app_id": "1:123456789:android:bbbb",
                "android_client_info": { "package_name": "app.exemple.admin" }
              },
              "api_key": [ { "current_key": "cle-publique-b" } ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun readsTheOptionsOfTheRequestedPackage() {
        assertEquals(
            FirebaseOptionsValues("1:123456789:android:bbbb", "cle-publique-b", "projet-test", "123456789"),
            FirebaseConfig.parse(json, "app.exemple.admin"),
        )
    }

    @Test
    fun returnsNullForAnUnknownPackageOrUnreadableFile() {
        assertNull(FirebaseConfig.parse(json, "app.autre"))
        assertNull(FirebaseConfig.parse("{pas du json", "app.exemple"))
        assertNull(FirebaseConfig.parse("[]", "app.exemple"))
    }

    @Test
    fun decodesRawOrBase64Secrets() {
        assertEquals(json, FirebaseConfig.decode(json))
        assertEquals(json, FirebaseConfig.decode(Base64.getEncoder().encodeToString(json.toByteArray())))
        assertNull(FirebaseConfig.decode("  "))
    }
}
