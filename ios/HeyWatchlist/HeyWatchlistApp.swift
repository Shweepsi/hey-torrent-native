import SwiftUI

@main
struct HeyWatchlistApp: App {
    @State private var model = AccountModel(client: LiveAccountClient())

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
        }
    }
}
