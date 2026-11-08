import CouchKit
import Foundation
import Network
import Observation

enum LinkState: Equatable { case offline, connecting, connected }

/// Everything the app knows about one paired TV, and its live session. Views read it; actions go through it.
@MainActor
@Observable
final class TVController: Identifiable {
    private(set) var tv: PairedTV
    private(set) var link: LinkState = .offline
    private(set) var state: TVState?
    private(set) var config: [String: JSONValue] = [:]
    private(set) var groups: [SettingsGroup] = []
    private(set) var apps: [AppEntry] = []
    private(set) var inputs: [InputEntry] = []
    private(set) var usage: UsageReport?
    private(set) var icons: [String: Data] = [:]
    private(set) var stream: StreamStatus?
    private(set) var wantsStream = false
    private(set) var hasPicture = false
    var quality: StreamQuality = .medium
    var banner: String?

    let video = VideoPipeline()
    nonisolated let id: String
    var displayName: String { state?.name.isEmpty == false ? (state?.name ?? tv.name) : tv.name }

    @ObservationIgnored private var session: PhoneSession?
    @ObservationIgnored private var loop: Task<Void, Never>?
    @ObservationIgnored private var sendChain: Task<Void, Never>?
    @ObservationIgnored private var requestedIcons: Set<String> = []
    @ObservationIgnored private let phoneId: String
    @ObservationIgnored private let resolve: @MainActor () -> NWEndpoint?
    @ObservationIgnored private let remember: @MainActor (PairedTV) -> Void
    @ObservationIgnored let isDemo: Bool

    init(tv: PairedTV, phoneId: String, resolve: @escaping @MainActor () -> NWEndpoint?, remember: @escaping @MainActor (PairedTV) -> Void) {
        self.tv = tv; self.id = tv.id; self.phoneId = phoneId; self.resolve = resolve; self.remember = remember; self.isDemo = false
        video.onFirstPicture = { [weak self] in self?.hasPicture = true }
    }

    /// Demo controller: filled with sample data, never touches the network.
    init(demo: DemoData) {
        tv = demo.tv; id = demo.tv.id; phoneId = ""; resolve = { nil }; remember = { _ in }; isDemo = true
        link = .connected; state = demo.state; config = demo.config; groups = demo.groups
        apps = demo.apps; inputs = demo.inputs; usage = demo.usage; quality = .medium
    }

    // MARK: lifecycle

    func connect() {
        guard !isDemo, loop == nil else { return }
        loop = Task { [weak self] in await self?.run() }
    }

    func disconnect() {
        guard !isDemo else { return }
        loop?.cancel(); loop = nil
        let s = session; session = nil
        Task { await s?.close() }
        link = .offline
        stream = nil
        video.reset()
        hasPicture = false
    }

    func update(_ tv: PairedTV) { self.tv = tv }

    private func run() async {
        var attempt = 0
        while !Task.isCancelled {
            link = .connecting
            let ok = await runOnce(attempt: attempt)
            if Task.isCancelled { return }
            link = .offline
            attempt = ok ? 0 : attempt + 1
            let delay = min(15.0, ok ? 1.0 : pow(2.0, Double(min(attempt, 4))))
            try? await Task.sleep(for: .seconds(delay))
        }
    }

    /// One connection. True when it got as far as a live session.
    private func runOnce(attempt: Int) async -> Bool {
        let found = resolve()
        let session: PhoneSession
        if let host = tv.lastHost, found == nil || attempt % 2 == 1 {
            session = PhoneSession(tv: tv, phoneId: phoneId, host: host)
        } else if let found {
            session = PhoneSession(tv: tv, phoneId: phoneId, endpoint: found)
        } else {
            return false
        }
        do { try await session.connect() } catch { return false }
        if Task.isCancelled { await session.close(); return false }
        self.session = session
        link = .connected
        if let ip = session.remoteIPv4, ip != tv.lastHost {
            tv.lastHost = ip
            remember(tv)
        }
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        try? await session.send(.hello(app: version))
        try? await session.send(.usage)
        if wantsStream { try? await session.send(.streamStart(quality)) }
        for await event in session.events { handle(event) }
        self.session = nil
        stream = nil
        video.reset()
        hasPicture = false
        return true
    }

    private func handle(_ event: SessionEvent) {
        switch event {
        case .videoConfig(let sps, let pps): video.configure(sps: sps, pps: pps)
        case .videoUnit(let key, let avcc): video.enqueue(avcc: avcc, keyframe: key)
        case .closed: break
        case .message(let m): handle(m)
        }
    }

    private func handle(_ message: IncomingMessage) {
        switch message {
        case .state(let s):
            state = s
            if let mac = s.mac, mac != tv.mac { tv.mac = mac; remember(tv) }
            if !s.name.isEmpty, s.name != tv.name { tv.name = s.name; remember(tv) }
        case .config(let c):
            config = c
        case .settings(let g): groups = g
        case .apps(let a): apps = a
        case .inputs(let i): inputs = i
        case .usage(let u): usage = u
        case .icon(let key, let png):
            if let png { icons[key] = png; IconCache.drop(key: key) }
        case .streamStatus(let s):
            stream = s
            if s.state == .ended || s.state == .error || s.state == .needsAdb { hasPicture = false }
        case .error(_, let text):
            banner = text
            Haptics.failure()
        case .pong, .unknown: break
        }
    }

    // MARK: sending

    func send(_ message: OutgoingMessage) {
        guard !isDemo, let s = session else { return }
        let previous = sendChain
        sendChain = Task { await previous?.value; try? await s.send(message) }
    }

    func press(_ key: RemoteKey, action: KeyAction = .tap) {
        Haptics.tap()
        send(.key(key, action))
    }

    func type(_ text: String) { if !text.isEmpty { send(.text(text)) } }
    func launch(_ app: AppEntry) { Haptics.press(); send(.launch(packageName: app.packageName)) }
    func switchInput(_ input: InputEntry) { Haptics.press(); send(.input(id: input.key)) }
    func refreshUsage() { send(.usage) }

    /// Optimistic: the screen changes now; the TV's `config` push is the final word.
    func set(_ patch: [String: JSONValue]) {
        for (k, v) in patch { config[k] = v }
        send(.configSet(patch))
    }

    func set(_ key: String, _ value: JSONValue) { set([key: value]) }

    func requestIcon(_ key: String) {
        guard !isDemo, icons[key] == nil, !requestedIcons.contains(key) else { return }
        requestedIcons.insert(key)
        send(.icon(key: key))
    }

    func startStream() {
        wantsStream = true
        hasPicture = false
        stream = StreamStatus(state: .starting)
        send(.streamStart(quality))
    }

    func stopStream() {
        wantsStream = false
        send(.streamStop)
        stream = nil
        video.reset()
        hasPicture = false
    }

    func setQuality(_ q: StreamQuality) {
        quality = q
        if wantsStream { send(.streamStart(q)) }
    }

    func dismissBanner() { banner = nil }

    // MARK: derived

    func label(forPackage p: String) -> String? { apps.first { $0.packageName == p }?.label }
}
