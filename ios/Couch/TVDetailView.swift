import CouchKit
import SwiftUI

struct TVDetailView: View {
    let controller: TVController
    @State private var tab: Page

    enum Page: Hashable { case remote, apps, settings, usage }

    init(controller: TVController, initialTab: DemoTab) {
        self.controller = controller
        switch initialTab {
        case .remote: _tab = State(initialValue: .remote)
        case .apps: _tab = State(initialValue: .apps)
        case .settings: _tab = State(initialValue: .settings)
        case .usage: _tab = State(initialValue: .usage)
        }
    }

    var body: some View {
        TabView(selection: $tab) {
            Tab("Remote", systemImage: "appletvremote.gen4.fill", value: Page.remote) {
                RemoteView(controller: controller)
            }
            Tab("Apps", systemImage: "square.grid.2x2.fill", value: Page.apps) {
                AppsView(controller: controller)
            }
            Tab("Settings", systemImage: "slider.horizontal.3", value: Page.settings) {
                SettingsView(controller: controller)
            }
            Tab("Screen time", systemImage: "chart.bar.fill", value: Page.usage) {
                UsageView(controller: controller)
            }
        }
        .navigationTitle(controller.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 6) {
                    StatusDot(kind: controller.link == .connected ? .connected : .offline)
                    if controller.link != .connected {
                        Text(controller.link == .connecting ? "Connecting…" : "Offline")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if let text = controller.banner ?? (controller.mayBeForgotten ? String(localized: "This TV may have forgotten this phone. Pair it again.") : nil) {
                HStack {
                    Image(systemName: "exclamationmark.circle.fill")
                    Text(text).font(.footnote)
                    Spacer()
                    Button("Dismiss", systemImage: "xmark") { controller.dismissBanner() }
                        .labelStyle(.iconOnly)
                }
                .padding(12)
                .glassEffect(.regular, in: .rect(cornerRadius: 14))
                .padding(.horizontal)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.snappy, value: controller.banner)
        .task(id: controller.banner) {
            guard controller.banner != nil else { return }
            try? await Task.sleep(for: .seconds(6))
            controller.dismissBanner()
        }
        .onAppear { controller.connect() }
    }
}
