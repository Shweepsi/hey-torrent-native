import Testing

@testable import HeyWatchlist

@MainActor
struct CoreStatusModelTests {
    @Test func startsInLoadingState() {
        let model = CoreStatusModel(client: FakeCoreClient(results: []))

        #expect(model.state == .loading)
    }

    @Test func loadsTheStatusFromTheClient() async {
        let model = CoreStatusModel(client: FakeCoreClient(results: [.success(.sample)]))

        await model.load()

        #expect(model.state == .loaded(.sample))
    }

    @Test func isLoadingWhileTheCoreHasNotAnswered() async {
        let client = GatedCoreClient()
        let model = CoreStatusModel(client: client)

        let loading = Task { await model.load() }
        await client.waitUntilRequested()
        #expect(model.state == .loading)

        await client.release(.success(.sample))
        await loading.value
        #expect(model.state == .loaded(.sample))
    }

    @Test func mapsACoreErrorToAnInternalFailure() async {
        let model = CoreStatusModel(client: FakeCoreClient(results: [.failure(CoreError.Internal)]))

        await model.load()

        #expect(model.state == .failed(.internalError))
    }

    @Test func mapsAnyOtherErrorToAnUnexpectedFailure() async {
        let model = CoreStatusModel(client: FakeCoreClient(results: [.failure(UnrelatedError())]))

        await model.load()

        #expect(model.state == .failed(.unexpected))
    }

    @Test func retryingAfterAFailureLoadsTheStatus() async {
        let client = FakeCoreClient(results: [.failure(CoreError.Internal), .success(.sample)])
        let model = CoreStatusModel(client: client)

        await model.load()
        #expect(model.state == .failed(.internalError))

        await model.load()
        #expect(model.state == .loaded(.sample))
        let calls = await client.callCount
        #expect(calls == 2)
    }
}
