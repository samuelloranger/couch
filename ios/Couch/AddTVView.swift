import CouchKit
import SwiftUI

enum PairTarget: Hashable {
    case found(DiscoveredTV)
    case address(String)

    /// One pairing session per TV (or typed address), however often a view is rebuilt.
    var key: String {
        switch self {
        case .found(let d): "tv:" + d.id
        case .address(let a): "addr:" + a
        }
    }

    var title: String {
        switch self {
        case .found(let d): d.name
        case .address(let a): a
        }
    }
}

struct AddTVView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var path: [PairTarget] = []
    @State private var address = ""
    @FocusState private var addressFocused: Bool

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    if model.addable.isEmpty {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Looking for TVs on this network…").foregroundStyle(.secondary)
                        }
                    }
                    ForEach(model.addable) { tv in
                        Button { path.append(.found(tv)) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "tv").foregroundStyle(Theme.accent).frame(width: 28)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(tv.name).foregroundStyle(.primary)
                                    if tv.pairing {
                                        Text("Waiting for a phone").font(.footnote).foregroundStyle(Theme.accent)
                                    }
                                }
                                Spacer()
                                Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
                            }
                        }
                    }
                } header: {
                    Text("On this network")
                } footer: {
                    Text("On the TV, open Customize, then Phone, then Pair a phone.")
                }

                Section {
                    TextField("192.168.1.20", text: $address)
                        .keyboardType(.numbersAndPunctuation)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($addressFocused)
                        .submitLabel(.go)
                        .onSubmit(goByAddress)
                    Button("Pair by address", action: goByAddress)
                        .disabled(trimmedAddress.isEmpty)
                } header: {
                    Text("By address")
                } footer: {
                    Text("Use this away from home, over your home VPN, where TVs can't be found automatically.")
                }
            }
            .navigationTitle("Add a TV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .navigationDestination(for: PairTarget.self) { target in
                PairingView(target: target) { dismiss() }
            }
            .onChange(of: path) { _, new in if new.isEmpty { model.pairings.cancelAll() } }
            .onDisappear { model.pairings.cancelAll() }
            .task {
                if model.demoMode, model.demoScreen == "pair", let first = model.discovered.first { path = [.found(first)] }
            }
        }
        .presentationDetents([.large])
    }

    private var trimmedAddress: String { address.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func goByAddress() {
        guard !trimmedAddress.isEmpty else { return }
        addressFocused = false
        path.append(.address(trimmedAddress))
    }
}

struct PairingView: View {
    @Environment(AppModel.self) private var model
    let target: PairTarget
    let onDone: () -> Void

    var body: some View {
        PairingContent(session: session, target: target, onDone: onDone)
    }

    /// Looked up by key each time, so a rebuilt view reuses the pairing already in flight.
    private var session: PairingSession {
        let phoneId = model.phoneId, phoneName = model.phoneName, demo = model.demoMode
        let target = self.target
        return model.pairings.session(key: target.key) {
            if demo { return DemoPairing() }
            switch target {
            case .found(let d):
                return try await PairingClient.begin(endpoint: d.endpoint, phoneId: phoneId, phoneName: phoneName)
            case .address(let a):
                let (host, port) = PairingContent.split(a)
                return try await PairingClient.begin(host: host, port: port, phoneId: phoneId, phoneName: phoneName)
            }
        }
    }
}

final class DemoPairing: PendingPairing, @unchecked Sendable {
    let tvName = "Bedroom TV"
    let code = "042431"
    func finish(matched: Bool) async throws -> PairedTV { PairedTV(id: "demo-new", name: tvName, linkKey: Data(count: 32)) }
    func cancel() {}
}

struct PairingContent: View {
    @Environment(AppModel.self) private var model
    let session: PairingSession
    let target: PairTarget
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 28) {
            Spacer()
            switch session.phase {
            case .idle, .connecting, .done:
                ProgressView().controlSize(.large)
                Text(connectingText).foregroundStyle(.secondary)
            case .finishing:
                ProgressView().controlSize(.large)
                Text(finishingText).foregroundStyle(.secondary)
            case .compare(let code):
                Text(compareTitle)
                    .font(.title3.weight(.medium))
                    .multilineTextAlignment(.center)
                Text(grouped(code))
                    .font(.system(size: 56, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(Theme.accent)
                    .accessibilityLabel(code.map(String.init).joined(separator: " "))
                    .padding(.vertical, 8)
                VStack(spacing: 12) {
                    Button { answer(true) } label: {
                        Text("They match").frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    Button { answer(false) } label: {
                        Text("They differ").frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.bordered)
                }
                .controlSize(.large)
            case .failed(let failure):
                Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(.yellow)
                Text(message(for: failure)).multilineTextAlignment(.center)
                Button("Try again") { session.retry() }.buttonStyle(.borderedProminent)
            }
            Spacer()
        }
        .padding(24)
        .navigationTitle("Pair")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(session.phase == .finishing)
        // start() is idempotent: a rebuilt view never opens a second connection.
        .task { session.start() }
        .onChange(of: session.phase) { _, phase in if phase == .done { Haptics.success(); onDone() } }
    }

    private var connectingText: LocalizedStringKey { "Connecting to \(target.title)…" }
    private var finishingText: LocalizedStringKey { "Finishing…" }
    private var compareTitle: LocalizedStringKey { "Does \(session.tvName ?? target.title) show these digits?" }

    private func grouped(_ code: String) -> String {
        guard code.count == 6 else { return code }
        return String(code.prefix(3)) + " " + String(code.suffix(3))
    }

    private func answer(_ matched: Bool) {
        let address: String? = { if case .address(let a) = target { return Self.split(a).0 } else { return nil } }()
        session.finish(matched: matched) { tv in
            var tv = tv
            if let address { tv.lastHost = address }
            if !model.demoMode { try model.add(tv) }
        }
    }

    private func message(for failure: PairingFailure) -> String {
        switch failure {
        case .notPairing: String(localized: "This TV isn't waiting for a phone. On the TV, open Customize, then Phone, then Pair a phone.")
        case .unreachable: String(localized: "Couldn't reach the TV. Check that you are on the same network.")
        case .protocolError: String(localized: "The TV sent something unexpected. Update Couch on the TV and try again.")
        case .declined: String(localized: "The TV didn't confirm. Try pairing again.")
        case .storage: String(localized: "Couldn't save this TV on the phone. Try pairing again.")
        case .dropped: String(localized: "The connection dropped. Try pairing again.")
        }
    }

    /// "host" or "host:port"; the port defaults to 47820.
    nonisolated static func split(_ address: String) -> (String, UInt16) {
        let parts = address.split(separator: ":", omittingEmptySubsequences: false)
        if parts.count == 2, let port = UInt16(parts[1]) { return (String(parts[0]), port) }
        return (address, PairingClient.port)
    }
}
