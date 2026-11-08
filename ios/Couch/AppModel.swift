import CouchKit
import Foundation
import Network
import Observation
import UIKit

@MainActor
@Observable
final class AppModel {
    private(set) var controllers: [TVController] = []
    private(set) var discovered: [DiscoveredTV] = []
    private(set) var backups: [ConfigBackup] = []
    let demoMode: Bool
    let pairings = PairingCoordinator()
    let demoScreen: String?

    @ObservationIgnored private let store: any PairedStore
    @ObservationIgnored private let browser = TVBrowser()
    @ObservationIgnored private let backupStore: BackupStore
    @ObservationIgnored private var browsing = false
    @ObservationIgnored private(set) var phoneId: String

    var phoneName: String { UIDevice.current.name }

    init(demo: Bool = false, demoScreen: String? = nil) {
        demoMode = demo
        self.demoScreen = demoScreen
        if demo {
            store = MemoryStore()
            phoneId = "demo-phone"
            backupStore = BackupStore(url: FileManager.default.temporaryDirectory.appendingPathComponent("couch-demo-backups.json"))
            controllers = DemoData.all.map { TVController(demo: $0) }
            backups = [
                ConfigBackup(name: "Weekend look", date: Date().addingTimeInterval(-86_400 * 3), groups: [.look, .ambient], patch: [:]),
            ]
            discovered = DemoData.discovered
        } else {
            let s = KeychainStore()
            store = s
            phoneId = s.phoneId()
            backupStore = BackupStore.standard()
            backups = backupStore.load()
            controllers = s.load().map(makeController)
        }
    }

    private func makeController(_ tv: PairedTV) -> TVController {
        let id = tv.id
        return TVController(
            tv: tv, phoneId: phoneId,
            resolve: { [weak self] in self?.discovered.first { $0.id == id }?.endpoint },
            remember: { [weak self] updated in try? self?.store.save(updated) })
    }

    // MARK: lifecycle

    func foreground() {
        startBrowsing()
        for c in controllers { c.connect() }
    }

    func background() {
        stopBrowsing()
        for c in controllers { c.disconnect() }
    }

    func startBrowsing() {
        guard !demoMode, !browsing else { return }
        browsing = true
        browser.start { [weak self] list in
            Task { @MainActor in self?.discovered = list }
        }
    }

    func stopBrowsing() {
        guard browsing else { return }
        browsing = false
        browser.stop()
        discovered = []
    }

    func isReachable(_ c: TVController) -> Bool { discovered.contains { $0.id == c.id } }

    func controller(id: String) -> TVController? { controllers.first { $0.id == id } }

    // MARK: pairing

    func add(_ tv: PairedTV) throws {
        try store.save(tv)
        if let existing = controller(id: tv.id) {
            existing.close()
            controllers.removeAll { $0.id == tv.id }
        }
        let c = makeController(tv)
        controllers.append(c)
        controllers.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        c.connect()
    }

    func forget(_ c: TVController) {
        c.close()
        store.remove(id: c.id)
        controllers.removeAll { $0.id == c.id }
    }

    /// TVs that can be added: found on the network and not yet paired.
    var addable: [DiscoveredTV] { discovered.filter { d in !controllers.contains { $0.id == d.id } } }

    // MARK: copy and backups

    func saveBackup(name: String, from source: TVController, groups: Set<CopyGroup>) {
        let patch = CopyPlan.patch(from: source.config, groups: groups)
        let backup = ConfigBackup(name: name, groups: CopyGroup.allCases.filter(groups.contains), patch: patch)
        backups = demoMode ? [backup] + backups : backupStore.add(backup)
    }

    func deleteBackup(_ b: ConfigBackup) {
        backups = demoMode ? backups.filter { $0.id != b.id } : backupStore.remove(id: b.id)
    }

    /// Sends `patch` to another paired TV over a short session of its own.
    func apply(patch: [String: JSONValue], to target: TVController) async -> Bool {
        let safe = CopyPlan.sanitized(patch)
        guard !safe.isEmpty else { return false }
        if demoMode { return true }
        if target.link == .connected { target.set(safe); return true }
        let session: PhoneSession
        if let found = discovered.first(where: { $0.id == target.id })?.endpoint {
            session = PhoneSession(tv: target.tv, phoneId: phoneId, endpoint: found)
        } else if let host = target.tv.lastHost {
            session = PhoneSession(tv: target.tv, phoneId: phoneId, host: host)
        } else {
            return false
        }
        do {
            try await session.connect()
            try await session.send(.configSet(safe))
            // `usage` goes through the same worker as config.set, so its answer means the change was handled.
            try await session.send(.usage)
            let wait = Task { () -> Bool in
                for await e in session.events {
                    switch e {
                    case .message(.usage): return true
                    case .message(.error(let about, _)) where about == "config.set": return false
                    case .closed: return false
                    default: break
                    }
                }
                return false
            }
            let timeout = Task { try? await Task.sleep(for: .seconds(8)); wait.cancel() }
            let ok = await wait.value
            timeout.cancel()
            await session.close()
            return ok
        } catch {
            await session.close()
            return false
        }
    }
}
