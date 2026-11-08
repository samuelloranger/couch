import CouchKit
import SwiftUI

@main
struct CouchApp: App {
    @State private var model = CouchApp.makeModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            switch phase {
            case .active: model.foreground()
            case .background: model.background()
            default: break
            }
        }
    }

    private static func makeModel() -> AppModel {
        #if DEBUG
        let env = ProcessInfo.processInfo.environment
        if env["COUCH_DEMO"] == "1" { return AppModel(demo: true, demoScreen: env["COUCH_DEMO_SCREEN"]) }
        #endif
        return AppModel()
    }
}
