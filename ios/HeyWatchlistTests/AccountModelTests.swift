import Foundation
import Testing

@testable import HeyWatchlist

@MainActor
struct AccountModelTests {
    @Test func startsSignedOutWithoutAStoredSession() async {
        let model = AccountModel(client: FakeAccountClient())
        #expect(model.state == .starting)
        await model.start()
        #expect(model.state == .signedOut(.init()))
        #expect(model.appleNonce == AppleNonce(raw: "brut", sha256: "condense"))
    }

    @Test func resumesAStoredSessionEvenOffline() async {
        let model = AccountModel(client: FakeAccountClient(stored: .camille))
        await model.start()
        #expect(model.state == .signedIn(.init(profile: .camille, coreVersion: "0.2.0")))
    }

    @Test func refreshUpdatesTheFirstName() async {
        let client = FakeAccountClient(stored: .camille)
        let renamed = Profile(firstName: "Cam", isAdmin: false, hasApple: true)
        await client.set(refresh: .success(renamed))
        let model = AccountModel(client: client)
        await model.start()
        #expect(model.state == .signedIn(.init(profile: renamed, coreVersion: "0.2.0")))
    }

    @Test func anExpiredSessionGoesBackToWelcome() async {
        let client = FakeAccountClient(stored: .camille)
        await client.set(refresh: .failure(.signedOut))
        let model = AccountModel(client: client)
        await model.start()
        #expect(model.state == .signedOut(.init(failure: .signedOut)))
    }

    @Test func aNewAppleAccountWaitsThenGetsInOnceAccepted() async {
        let client = FakeAccountClient()
        let model = AccountModel(client: client)
        await model.start()

        await model.completeApple(idToken: "jeton-apple", givenName: "Camille")
        #expect(model.state == .signedIn(.init(profile: .camillePending, coreVersion: "0.2.0")))

        await client.set(refresh: .success(.camille))
        await model.checkAccess()
        #expect(model.state == .signedIn(.init(profile: .camille, coreVersion: "0.2.0")))
    }

    @Test func aFailedCheckKeepsWaitingAndExplains() async {
        let model = AccountModel(client: FakeAccountClient(stored: .camillePending))
        await model.start()
        await model.checkAccess()
        #expect(model.state == .signedIn(.init(profile: .camillePending, coreVersion: "0.2.0", failure: .network)))
    }

    @Test func aRefusedAccountStaysSignedIn() async {
        let client = FakeAccountClient(stored: .camillePending)
        let refused = Profile(firstName: "Camille", isAdmin: false, hasApple: true, access: .refused)
        await client.set(refresh: .success(refused))
        let model = AccountModel(client: client)
        await model.start()
        #expect(model.state == .signedIn(.init(profile: refused, coreVersion: "0.2.0")))
    }

    @Test func closingTheGoogleWindowShowsNoError() async {
        let model = AccountModel(client: FakeAccountClient())
        await model.start()
        await model.signInWithGoogle { _, _ in throw AccountFailure.cancelled }
        #expect(model.state == .signedOut(.init()))
    }

    @Test func googleNotConfiguredOnTheServerIsExplained() async {
        let client = FakeAccountClient()
        await client.set(googleIosClient: false)
        let model = AccountModel(client: client)
        await model.start()
        await model.signInWithGoogle(authenticate: fakeWebAuthentication)
        #expect(model.state == .signedOut(.init(failure: .providerUnavailable)))
    }

    @Test func signOutReturnsToWelcome() async {
        let client = FakeAccountClient(stored: .camille)
        let model = AccountModel(client: client)
        await model.start()
        await model.signOut()
        #expect(await client.signedOut)
        #expect(model.state == .signedOut(.init()))
    }

    @Test func anExpiredSessionSeenByTheCatalogGoesBackToWelcome() async {
        let model = AccountModel(client: FakeAccountClient(stored: .camille))
        await model.start()
        await model.sessionExpired()
        #expect(model.state == .signedOut(.init(failure: .signedOut)))
    }

    @Test func anExpiredSessionIsIgnoredWhenAlreadySignedOut() async {
        let model = AccountModel(client: FakeAccountClient())
        await model.start()
        await model.sessionExpired()
        #expect(model.state == .signedOut(.init()))
    }

    @Test func deletionSendsTheAppleCode() async {
        let client = FakeAccountClient(stored: .camille)
        let model = AccountModel(client: client)
        await model.start()
        await model.deleteAccount(appleAuthorizationCode: "code-apple")
        #expect(await client.deletedWithCode == .some("code-apple"))
        #expect(model.state == .signedOut(.init()))
    }

    @Test func aFailedDeletionKeepsTheAccountAndExplains() async {
        let client = FakeAccountClient(stored: .camille)
        await client.set(delete: .failure(.network))
        let model = AccountModel(client: client)
        await model.start()
        await model.deleteAccount(appleAuthorizationCode: nil)
        #expect(model.state == .signedIn(.init(profile: .camille, coreVersion: "0.2.0", failure: .network)))
    }
}
