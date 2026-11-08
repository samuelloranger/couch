import CouchKit
import SwiftUI

enum PairTarget: Hashable {
    case found(DiscoveredTV)
    case address(String)

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

    private enum Phase: Equatable {
        case connecting
        case compare(String)
        case finishing
        case failed(String)
    }

    @State private var phase: Phase = .connecting
    @State private var pending: PairingInProgress?
    @State private var attempt = 0

    var body: some View {
        VStack(spacing: 28) {
            Spacer()
            switch phase {
            case .connecting, .finishing:
                ProgressView().controlSize(.large)
                Text(phase == .connecting ? connectingText : finishingText)
                    .foregroundStyle(.secondary)
            case .compare(let code):
                Text("Does \(target.title) show these digits?")
                    .font(.title3.weight(.medium))
                    .multilineTextAlignment(.center)
                Text(grouped(code))
                    .font(.system(size: 56, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(Theme.accent)
                    .accessibilityLabel(code.map(String.init).joined(separator: " "))
                    .padding(.vertical, 8)
                VStack(spacing: 12) {
                    Button { finish(true) } label: {
                        Text("They match").frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    Button { finish(false) } label: {
                        Text("They differ").frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.bordered)
                }
                .controlSize(.large)
            case .failed(let message):
                Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(.yellow)
                Text(message).multilineTextAlignment(.center)
                Button("Try again") { attempt += 1 }.buttonStyle(.borderedProminent)
            }
            Spacer()
        }
        .padding(24)
        .navigationTitle("Pair")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(phase == .finishing)
        .task(id: attempt) { await begin() }
        .onDisappear { pending?.cancel() }
    }

    private var connectingText: LocalizedStringKey { "Connecting to \(target.title)…" }
    private var finishingText: LocalizedStringKey { "Finishing…" }

    private func grouped(_ code: String) -> String {
        guard code.count == 6 else { return code }
        return String(code.prefix(3)) + " " + String(code.suffix(3))
    }

    private func begin() async {
        phase = .connecting
        if model.demoMode {
            phase = .compare("042 431".replacingOccurrences(of: " ", with: ""))
            return
        }
        do {
            let p: PairingInProgress
            switch target {
            case .found(let d):
                p = try await PairingClient.begin(endpoint: d.endpoint, phoneId: model.phoneId, phoneName: model.phoneName)
            case .address(let a):
                let (host, port) = Self.split(a)
                p = try await PairingClient.begin(host: host, port: port, phoneId: model.phoneId, phoneName: model.phoneName)
            }
            pending = p
            phase = .compare(p.code)
        } catch let e as PairingError {
            phase = .failed(message(for: e))
        } catch {
            phase = .failed(String(localized: "Couldn't reach the TV."))
        }
    }

    private func finish(_ matched: Bool) {
        guard let p = pending else { if matched { onDone() } else { onDone() }; return }
        phase = .finishing
        Task {
            do {
                var tv = try await p.finish(matched: matched)
                if case .address(let a) = target { tv.lastHost = Self.split(a).0 }
                try model.add(tv)
                Haptics.success()
                onDone()
            } catch PairingError.declined {
                if matched { phase = .failed(String(localized: "The TV didn't confirm. Try pairing again.")) } else { onDone() }
            } catch is StoreError {
                phase = .failed(String(localized: "Couldn't save this TV on the phone. Try pairing again."))
            } catch {
                phase = .failed(String(localized: "The connection dropped. Try pairing again."))
            }
        }
    }

    private func message(for e: PairingError) -> String {
        switch e {
        case .notPairing: String(localized: "This TV isn't waiting for a phone. On the TV, open Customize, then Phone, then Pair a phone.")
        case .unreachable: String(localized: "Couldn't reach the TV. Check that you are on the same network.")
        case .protocolError: String(localized: "The TV sent something unexpected. Update Couch on the TV and try again.")
        case .declined: String(localized: "Pairing was cancelled.")
        }
    }

    /// "host" or "host:port"; the port defaults to 47820.
    static func split(_ address: String) -> (String, UInt16) {
        let parts = address.split(separator: ":", omittingEmptySubsequences: false)
        if parts.count == 2, let port = UInt16(parts[1]) { return (String(parts[0]), port) }
        return (address, PairingClient.port)
    }
}
