import CouchKit
import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var path: [String] = []
    @State private var showAdd = false
    @State private var showPairDemo = false

    var body: some View {
        NavigationStack(path: $path) {
            TVListView(showAdd: $showAdd)
                .navigationDestination(for: String.self) { id in
                    if let controller = model.controller(id: id) {
                        if model.demoMode && model.demoScreen == "wallpapers" {
                            WallpaperGalleryView(controller: controller)
                        } else {
                            TVDetailView(controller: controller, initialTab: DemoTab(rawValue: model.demoScreen == "full" ? "remote" : (model.demoScreen ?? "")) ?? .remote)
                        }
                    }
                }
        }
        .sheet(isPresented: $showAdd, onDismiss: { model.pairings.cancelAll() }) { AddTVView() }
        .task {
            guard model.demoMode, let screen = model.demoScreen else { return }
            if screen == "add" || screen == "pair" { showAdd = true }
            else if screen == "wallpapers" || DemoTab(rawValue: screen == "full" ? "remote" : screen) != nil,
                    let first = model.controllers.first { path = [first.id] }
        }
    }
}

enum DemoTab: String { case remote, apps, settings, usage }
