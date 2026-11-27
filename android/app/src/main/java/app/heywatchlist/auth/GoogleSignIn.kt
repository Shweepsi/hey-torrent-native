package app.heywatchlist.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import app.heywatchlist.core.AccountFailure
import app.heywatchlist.core.AccountFailureException
import app.heywatchlist.core.IdentityCredential
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * « Se connecter avec Google » par le Credential Manager du système. Il ne fait
 * qu'obtenir le jeton d'identité ; sa vérification revient au serveur, par le
 * cœur. `context` doit être l'activité affichée (la fenêtre s'y attache).
 */
suspend fun requestGoogleCredential(context: Context, serverClientId: String, nonce: String): IdentityCredential {
    val option = GetSignInWithGoogleOption.Builder(serverClientId)
        .setNonce(nonce)
        .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val response = try {
        CredentialManager.create(context).getCredential(context, request)
    } catch (error: GetCredentialCancellationException) {
        // Les services Google signalent aussi par une « annulation » une app mal
        // déclarée chez Google (empreinte SHA-1 ou client OAuth Android absents),
        // juste après le choix du compte : code 16, « Account reauth failed ».
        val failure = if (error.isGoogleRefusal()) AccountFailure.ProviderUnavailable else AccountFailure.Cancelled
        throw AccountFailureException(failure, error)
    } catch (error: GetCredentialException) {
        // Pas de compte Google, services Google absents, application mal déclarée…
        throw AccountFailureException(AccountFailure.ProviderUnavailable, error)
    }
    val credential = response.credential
    if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        throw AccountFailureException(AccountFailure.InvalidIdentity)
    }
    val google = try {
        GoogleIdTokenCredential.createFrom(credential.data)
    } catch (error: GoogleIdTokenParsingException) {
        throw AccountFailureException(AccountFailure.InvalidIdentity, error)
    }
    return IdentityCredential(idToken = google.idToken, nonce = nonce, givenName = google.givenName)
}

/** Refus des services Google (code 16) présenté comme une annulation. */
private fun GetCredentialCancellationException.isGoogleRefusal(): Boolean {
    val text = message.orEmpty()
    return text.contains("[16]") || text.contains("reauth", ignoreCase = true)
}
