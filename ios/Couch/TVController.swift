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
    /// Low by default (kind to weak Wi-Fi); the last choice is remembered per TV.
    var quality: StreamQuality = .low
    var banner: String?
    /// Several connections reached the TV but it never answered the handshake.
    private(set) var mayBeForgotten = false

    @ObservationIgnored private(set) var video: VideoPipeline!
    nonisolated let id: String
    var displayName: String { state?.name.isEmpty == false ? (state?.name ?? tv.name) : tv.name }

    @ObservationIgnored private var session: PhoneSession?
    @ObservationIgnored private var loop: Task<Void, Never>?
    @ObservationIgnored private var wantedIcons: Set<String> = []
    @ObservationIgnored private var noReplyCount = 0
    @ObservationIgnored private var pending = PendingQueue()
    @ObservationIgnored private var closed = false
    @ObservationIgnored private var lastAttempt: ContinuousClock.Instant?
    @ObservationIgnored private var backoff: Task<Void, Never>?
    @ObservationIgnored private var nudged = false
    @ObservationIgnored private var keyChain: Task<Void, Never>?
    @ObservationIgnored private var sendChain: Task<Void, Never>?
    @ObservationIgnored private var requestedIcons: Set<String> = []
    @ObservationIgnored private let phoneId: String
    @ObservationIgnored private let resolve: @MainActor () -> NWEndpoint?
    @ObservationIgnored private let remember: @MainActor (PairedTV) -> Void
    @ObservationIgnored let isDemo: Bool

    init(tv: PairedTV, phoneId: String, resolve: @escaping @MainActor () -> NWEndpoint?, remember: @escaping @MainActor (PairedTV) -> Void) {
        self.tv = tv; self.id = tv.id; self.phoneId = phoneId; self.resolve = resolve; self.remember = remember; self.isDemo = false
        installVideo()
        if let raw = UserDefaults.standard.string(forKey: "quality." + tv.id), let q = StreamQuality(rawValue: raw) { quality = q }
    }

    private func installVideo() {
        video = VideoPipeline(
            onFirstPicture: { [weak self] in Task { @MainActor in self?.hasPicture = true } },
            onLayerChange: { [weak self] in
                // The picture moved to a new layer: have the TV replay config and a keyframe.
                Task { @MainActor in
                    guard let self, self.wantsStream else { return }
                    self.send(.streamStop)
                    self.send(.streamStart(self.quality))
                }
            })
    }

    /// Demo controller: filled with sample data, never touches the network.
    init(demo: DemoData) {
        tv = demo.tv; id = demo.tv.id; phoneId = ""; resolve = { nil }; remember = { _ in }; isDemo = true
        link = .connected; state = demo.state; config = demo.config; groups = demo.groups
        apps = demo.apps; inputs = demo.inputs; usage = demo.usage; quality = .medium
        installVideo()
    }

    // MARK: lifecycle

    func connect() {
        guard !isDemo, !closed, loop == nil else { return }
        loop = Task { [weak self] in await self?.run() }
    }

    func disconnect() {
        guard !isDemo else { return }
        loop?.cancel(); loop = nil
        backoff?.cancel(); backoff = nil
        pending.clear()
        nudged = false
        let s = session; session = nil
        Task { await s?.close() }
        link = .offline
        stream = nil
        video.reset()
        hasPicture = false
    }

    /// The controller was replaced or forgotten: stop for good, so a stale reference can't reconnect.
    func close() {
        closed = true
        disconnect()
    }

    func update(_ tv: PairedTV) { self.tv = tv }

    private func run() async {
        var attempt = 0
        while !Task.isCancelled {
            // At least 500 ms between attempts, however often a press nudges the loop.
            if let last = lastAttempt {
                let gap = ContinuousClock.now - last
                if gap < .milliseconds(500) { try? await Task.sleep(for: .milliseconds(500) - gap) }
                if Task.isCancelled { return }
            }
            lastAttempt = .now
            link = .connecting
            let ok = await runOnce(attempt: attempt)
            if Task.isCancelled { return }
            link = .offline
            attempt = ok ? 0 : attempt + 1
            // A press during the attempt asked for an immediate retry; honour it before any wait.
            if nudged { nudged = false; attempt = 0; continue }
            let delay = min(15.0, ok ? 1.0 : pow(2.0, Double(min(attempt, 4))))
            let nap = Task<Void, Never> { try? await Task.sleep(for: .seconds(delay)) }
            backoff = nap
            await withTaskCancellationHandler { await nap.value } onCancel: { nap.cancel() }
            if backoff == nap { backoff = nil }
            if nudged { nudged = false; attempt = 0 }
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
        session.setVideoSink(video)  // before connect, so no early frame is counted and never consumed
        do {
            try await session.connect()
            noReplyCount = 0
            mayBeForgotten = false
        } catch SessionError.noReply {
            noReplyCount += 1
            if noReplyCount >= 3 { mayBeForgotten = true }
            return false
        } catch { return false }
        if Task.isCancelled { await session.close(); return false }
        self.session = session
        requestedIcons.removeAll()
        nudged = false
        link = .connected
        if let ip = session.remoteIPv4, ip != tv.lastHost {
            tv.lastHost = ip
            remember(tv)
        }
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        // No suspension from here to the end of this block: everything goes through one serial path,
        // hello first, then what was pressed while connecting, then bulk requests, so a live press
        // that arrives afterwards can never overtake an older one.
        dispatch(session, .hello(app: version), priorityPath: true)
        for message in pending.drain() { dispatch(session, message, priorityPath: true) }
        // Bulk requests keep their own order, but only start once hello and the flushed presses are out;
        // a live key pressed afterwards never waits behind them.
        let keysSoFar = keyChain
        sendChain = Task { [previous = sendChain] in await previous?.value; await keysSoFar?.value }
        dispatch(session, .usage, priorityPath: false)
        for key in wantedIcons.subtracting(icons.keys) {
            requestedIcons.insert(key)
            dispatch(session, .icon(key: key), priorityPath: false)
        }
        wantedIcons.removeAll()
        if wantsStream { dispatch(session, .streamStart(quality), priorityPath: true) }
        for await event in session.events { handle(event, from: session) }
        // A newer session may already own the controller; only clear what is ours.
        if self.session === session {
            self.session = nil
            stream = nil
            video.reset()
            hasPicture = false
        }
        return true
    }

    private func handle(_ event: SessionEvent, from session: PhoneSession) {
        switch event {
        case .videoConfig, .videoUnit: break  // video goes to the sink, off the main actor
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
            if s.state == .ended || s.state == .error || s.state == .needsAdb { hasPicture = false; video.reset() }
        case .error(let about, let text):
            if about == "icon" { requestedIcons.removeAll() }
            banner = text
            Haptics.failure()
        case .pong, .unknown: break
        }
    }

    // MARK: sending

    func send(_ message: OutgoingMessage) {
        guard !isDemo, !closed else { return }
        guard let s = session else {
            // Not connected: hold what the person pressed and reconnect now, not at the next retry.
            // Only the first press of a burst asks for a reconnect; repeats of queued keys don't re-nudge.
            let first = pending.liveCount() == 0
            if pending.append(message), first { reconnectNow() }
            return
        }
        // Keys and stream control never wait behind icon, usage or config traffic.
        dispatch(s, message, priorityPath: message.isPriority)
    }

    private func dispatch(_ s: PhoneSession, _ message: OutgoingMessage, priorityPath: Bool) {
        if priorityPath {
            let previous = keyChain
            keyChain = Task { await previous?.value; try? await s.send(message) }
        } else {
            let previous = sendChain
            sendChain = Task { await previous?.value; try? await s.send(message) }
        }
    }

    /// Cancels the current backoff wait so the next attempt starts immediately.
    func reconnectNow() {
        guard !closed else { return }
        if loop == nil { connect(); return }
        nudged = true
        backoff?.cancel()
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
        guard session != nil else { wantedIcons.insert(key); return }
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
        UserDefaults.standard.set(q.rawValue, forKey: "quality." + tv.id)
        if wantsStream { send(.streamStart(q)) }
    }

    func dismissBanner() { banner = nil }

    // MARK: derived

    func label(forPackage p: String) -> String? { apps.first { $0.packageName == p }?.label }
}
