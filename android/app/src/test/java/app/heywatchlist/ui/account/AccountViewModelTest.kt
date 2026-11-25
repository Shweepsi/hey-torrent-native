package app.heywatchlist.ui.account

import app.heywatchlist.R
import app.heywatchlist.core.AccountFailure
import app.heywatchlist.core.AccountFailureException
import app.heywatchlist.core.AccountGateway
import app.heywatchlist.core.IdentityCredential
import app.heywatchlist.core.Profile
import app.heywatchlist.core.SignInOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** États du compte, sans bibliothèque native (cœur simulé). */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val camille = Profile(firstName = "Camille", isAdmin = false)
    private val credential = IdentityCredential(idToken = "jeton", nonce = "nonce", givenName = "Camille")
    private val google: GoogleCredentialRequester = { _, _ -> credential }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeGateway(
        var stored: Profile? = null,
        var refreshed: () -> Profile = { throw AccountFailureException(AccountFailure.Network) },
        var signInAnswer: (String?) -> SignInOutcome = { SignInOutcome.InvitationRequired },
        var deleteAnswer: () -> Unit = {},
        var clientId: String? = "web.apps.googleusercontent.com",
    ) : AccountGateway {
        val codes = mutableListOf<String?>()
        var signedOut = false

        override suspend fun coreVersion() = "0.2.0"
        override suspend fun currentUser() = stored
        override suspend fun refreshProfile() = refreshed()
        override suspend fun googleWebClientId() = clientId
        override suspend fun newNonce() = "nonce"
        override suspend fun signIn(credential: IdentityCredential, invitationCode: String?): SignInOutcome {
            codes += invitationCode
            return signInAnswer(invitationCode)
        }
        override suspend fun signOut() {
            signedOut = true
        }
        override suspend fun deleteAccount() = deleteAnswer()
    }

    @Test
    fun startsSignedOutWithoutAStoredSession() = runTest(dispatcher) {
        val viewModel = AccountViewModel(FakeGateway())
        assertEquals(AccountUiState.Starting, viewModel.uiState.value)
        advanceUntilIdle()
        assertEquals(AccountUiState.SignedOut(), viewModel.uiState.value)
    }

    @Test
    fun resumesAStoredSessionEvenOffline() = runTest(dispatcher) {
        val viewModel = AccountViewModel(FakeGateway(stored = camille))
        advanceUntilIdle()
        assertEquals(AccountUiState.SignedIn(camille, "0.2.0"), viewModel.uiState.value)
    }

    @Test
    fun anExpiredSessionGoesBackToWelcome() = runTest(dispatcher) {
        val gateway = FakeGateway(stored = camille, refreshed = { throw AccountFailureException(AccountFailure.SignedOut) })
        val viewModel = AccountViewModel(gateway)
        advanceUntilIdle()
        assertEquals(AccountUiState.SignedOut(errorRes = R.string.error_signed_out), viewModel.uiState.value)
    }

    @Test
    fun asksForAnInvitationThenSignsInWithTheSameToken() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = AccountViewModel(gateway)
        advanceUntilIdle()

        viewModel.signInWithGoogle(google)
        advanceUntilIdle()
        assertEquals(AccountUiState.SignedOut(askInvitation = true), viewModel.uiState.value)

        gateway.signInAnswer = { code ->
            if (code == "ABCD-EFGH-JKMN") SignInOutcome.SignedIn(camille) else SignInOutcome.InvitationRequired
        }
        viewModel.submitInvitation("  ABCD-EFGH-JKMN ")
        advanceUntilIdle()
        assertEquals(AccountUiState.SignedIn(camille, "0.2.0"), viewModel.uiState.value)
        assertEquals(listOf(null, "ABCD-EFGH-JKMN"), gateway.codes)
    }

    @Test
    fun aWrongCodeKeepsTheInvitationStep() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = AccountViewModel(gateway)
        advanceUntilIdle()
        viewModel.signInWithGoogle(google)
        advanceUntilIdle()

        gateway.signInAnswer = { throw AccountFailureException(AccountFailure.InvitationInvalid) }
        viewModel.submitInvitation("FAUX")
        advanceUntilIdle()
        assertEquals(
            AccountUiState.SignedOut(askInvitation = true, errorRes = R.string.error_invitation_invalid),
            viewModel.uiState.value,
        )
    }

    @Test
    fun closingTheGoogleWindowShowsNoError() = runTest(dispatcher) {
        val viewModel = AccountViewModel(FakeGateway())
        advanceUntilIdle()
        viewModel.signInWithGoogle { _, _ -> throw AccountFailureException(AccountFailure.Cancelled) }
        advanceUntilIdle()
        assertEquals(AccountUiState.SignedOut(), viewModel.uiState.value)
    }

    @Test
    fun googleNotConfiguredOnTheServerIsExplained() = runTest(dispatcher) {
        val viewModel = AccountViewModel(FakeGateway(clientId = null))
        advanceUntilIdle()
        viewModel.signInWithGoogle(google)
        advanceUntilIdle()
        assertEquals(AccountUiState.SignedOut(errorRes = R.string.error_provider_unavailable), viewModel.uiState.value)
    }

    @Test
    fun signOutReturnsToWelcome() = runTest(dispatcher) {
        val gateway = FakeGateway(stored = camille)
        val viewModel = AccountViewModel(gateway)
        advanceUntilIdle()
        viewModel.signOut()
        advanceUntilIdle()
        assertTrue(gateway.signedOut)
        assertEquals(AccountUiState.SignedOut(), viewModel.uiState.value)
    }

    @Test
    fun deletionAsksForConfirmationFirst() = runTest(dispatcher) {
        val viewModel = AccountViewModel(FakeGateway(stored = camille))
        advanceUntilIdle()
        viewModel.requestDeletion()
        assertEquals(AccountUiState.SignedIn(camille, "0.2.0", confirmingDeletion = true), viewModel.uiState.value)
        viewModel.cancelDeletion()
        assertEquals(AccountUiState.SignedIn(camille, "0.2.0"), viewModel.uiState.value)

        viewModel.requestDeletion()
        viewModel.confirmDeletion()
        advanceUntilIdle()
        assertEquals(AccountUiState.SignedOut(), viewModel.uiState.value)
    }

    @Test
    fun aFailedDeletionKeepsTheAccountAndExplains() = runTest(dispatcher) {
        val gateway = FakeGateway(stored = camille, deleteAnswer = { throw AccountFailureException(AccountFailure.Network) })
        val viewModel = AccountViewModel(gateway)
        advanceUntilIdle()
        viewModel.requestDeletion()
        viewModel.confirmDeletion()
        advanceUntilIdle()
        assertEquals(AccountUiState.SignedIn(camille, "0.2.0", errorRes = R.string.error_network), viewModel.uiState.value)
    }
}
