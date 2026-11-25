import SwiftUI

@main
struct HeyWatchlistApp: App {
    @State private var model = CoreStatusModel(client: LiveCoreClient())

    var body: some Scene {
        WindowGroup {
            HomeView(model: model)
        }
    }
}
