import CouchKit
import SwiftUI

struct TVListView: View {
    @Environment(AppModel.self) private var model
    @Binding var showAdd: Bool
    @State private var forgetting: TVController?

    var body: some View {
        Group {
            if model.controllers.isEmpty {
                ContentUnavailableView {
                    Label("No TVs yet", systemImage: "tv")
                } description: {
                    Text("Add your TV to control it from here. Both need to be on the same network.")
                } actions: {
                    Button("Add a TV") { showAdd = true }.buttonStyle(.borderedProminent)
                }
            } else {
                List {
                    Section {
                        ForEach(model.controllers) { c in
                            NavigationLink(value: c.id) { TVRow(controller: c, reachable: model.isReachable(c)) }
                                .swipeActions {
                                    Button("Forget", role: .destructive) { forgetting = c }
                                }
                                .contextMenu {
                                    Button("Forget", systemImage: "trash", role: .destructive) { forgetting = c }
                                }
                        }
                    }
                    Section {
                        Button { showAdd = true } label: { Label("Add a TV", systemImage: "plus.circle.fill") }
                    }
                }
            }
        }
        .navigationTitle("Couch")
        .confirmationDialog(
            "Forget this TV?", isPresented: Binding(get: { forgetting != nil }, set: { if !$0 { forgetting = nil } }),
            titleVisibility: .visible, presenting: forgetting
        ) { c in
            Button("Forget \(c.displayName)", role: .destructive) { model.forget(c) }
        } message: { _ in
            Text("You will need to pair it again to control it.")
        }
    }
}

struct StatusDot: View {
    enum Kind { case connected, reachable, offline }
    let kind: Kind

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 10, height: 10)
            .accessibilityLabel(label)
    }

    private var color: Color {
        switch kind {
        case .connected: Theme.accent
        case .reachable: .yellow
        case .offline: .gray.opacity(0.6)
        }
    }

    private var label: LocalizedStringKey {
        switch kind {
        case .connected: "Connected"
        case .reachable: "On the network"
        case .offline: "Offline"
        }
    }
}

struct TVRow: View {
    let controller: TVController
    let reachable: Bool

    private var kind: StatusDot.Kind {
        if controller.link == .connected { return .connected }
        return reachable ? .reachable : .offline
    }

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "tv.fill")
                .font(.title2)
                .foregroundStyle(Theme.accent)
                .frame(width: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(controller.displayName).font(.headline)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            StatusDot(kind: kind)
        }
        .padding(.vertical, 4)
    }

    private var subtitle: LocalizedStringKey {
        switch kind {
        case .connected: "Connected"
        case .reachable: controller.link == .connecting ? "Connecting…" : "On the network"
        case .offline: "Offline"
        }
    }
}
