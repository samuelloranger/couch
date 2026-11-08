import Foundation
import Network

public enum SessionEvent: Sendable {
    case message(IncomingMessage)
    case videoConfig(sps: Data, pps: Data)
    case videoUnit(keyframe: Bool, avcc: Data)
    case closed(String?)
}

/// Bounds how much video can queue ahead of the screen: past ~60 frames or 2 MB, delta frames are
/// dropped until the next keyframe (never JSON, never config).
public final class VideoBacklog: @unchecked Sendable {
    public static let maxBytes = 2 * 1024 * 1024
    public static let maxFrames = 60
    private let lock = NSLock()
    private var bytes = 0
    private var frames = 0
    private var dropping = false

    public init() {}

    /// Reader side: true when the frame should be delivered.
    public func admit(bytes size: Int, keyframe: Bool) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if keyframe { dropping = false }
        else if dropping { return false }
        else if bytes + size > Self.maxBytes || frames >= Self.maxFrames { dropping = true; return false }
        bytes += size; frames += 1
        return true
    }

    /// Consumer side: a delivered frame has been handled.
    public func consumed(bytes size: Int) {
        lock.lock(); defer { lock.unlock() }
        bytes = max(0, bytes - size); frames = max(0, frames - 1)
    }

    public func reset() { lock.lock(); bytes = 0; frames = 0; dropping = false; lock.unlock() }
}

final class Heartbeat: @unchecked Sendable {
    private let lock = NSLock()
    private var last = ContinuousClock.now
    func touch() { lock.lock(); last = .now; lock.unlock() }
    var silentFor: Duration { lock.lock(); defer { lock.unlock() }; return ContinuousClock.now - last }
}

/// Receives video straight from the session's read loop, off the main actor.
public protocol VideoSink: AnyObject, Sendable {
    func videoConfig(sps: Data, pps: Data)
    /// `release` must be called once the frame has been handled, so the backlog bound can advance.
    func videoUnit(avcc: Data, keyframe: Bool, release: @escaping @Sendable () -> Void)
}

final class SinkBox: @unchecked Sendable {
    private let lock = NSLock()
    private var sink: (any VideoSink)?
    var value: (any VideoSink)? { get { lock.lock(); defer { lock.unlock() }; return sink } set { lock.lock(); sink = newValue; lock.unlock() } }
}

public enum SessionError: Error, Equatable {
    case handshakeFailed(String)
    /// TCP connected but the TV closed or stalled without answering: it may have forgotten this phone.
    case noReply
    case notConnected
}

/// The long-lived encrypted session to one TV (spec section 3): handshake, framing, keep-alive.
/// Events arrive on `events`; the stream finishes after `.closed`.
public actor PhoneSession {
    public nonisolated let events: AsyncStream<SessionEvent>
    private let continuation: AsyncStream<SessionEvent>.Continuation
    private let channel: TCPChannel
    private var sendCipher: FrameCipher?
    private var readTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private var finished = false
    private let tv: PairedTV
    private let phoneId: String

    /// Video the consumer has not handled yet; the reader drops delta frames when it grows.
    public nonisolated let backlog = VideoBacklog()
    private nonisolated let sinkBox = SinkBox()

    /// Set before `connect()`: video goes to the sink on the read loop's thread, never through `events`.
    public nonisolated func setVideoSink(_ sink: (any VideoSink)?) { sinkBox.value = sink }

    public nonisolated var remoteIPv4: String? { channel.remoteIPv4 }

    public init(tv: PairedTV, phoneId: String, host: String) {
        self.tv = tv; self.phoneId = phoneId
        channel = TCPChannel(host: host, port: PairingClient.port)
        (events, continuation) = AsyncStream.makeStream(of: SessionEvent.self, bufferingPolicy: .unbounded)
    }

    public init(tv: PairedTV, phoneId: String, endpoint: NWEndpoint) {
        self.tv = tv; self.phoneId = phoneId
        channel = TCPChannel(endpoint: endpoint)
        (events, continuation) = AsyncStream.makeStream(of: SessionEvent.self, bufferingPolicy: .unbounded)
    }

    /// Opens the socket and runs the handshake; on return the session is live and reading.
    public func connect() async throws {
        do {
            try await channel.open()
            let cn = CouchCrypto.randomBytes(16)
            try await channel.sendPlain([
                "op": .string("phone"), "id": .string(phoneId), "nonce": .string(b64(cn)),
                "proof": .string(b64(CouchCrypto.phoneProof(link: tv.linkKey, clientNonce: cn, phoneId: phoneId))),
            ])
            let reply: [String: JSONValue]
            do { reply = try await channel.receivePlain(timeout: 10) } catch { throw SessionError.noReply }
            guard let sn = reply["nonce"]?.stringValue.flatMap(unb64), sn.count == 16,
                  let proof = reply["proof"]?.stringValue.flatMap(unb64)
            else { throw SessionError.handshakeFailed("bad reply") }
            let expected = CouchCrypto.tvProof(link: tv.linkKey, clientNonce: cn, serverNonce: sn)
            guard Self.constantTimeEqual(proof, expected) else { throw SessionError.handshakeFailed("TV proof mismatch") }
            let keys = CouchCrypto.sessionKeys(link: tv.linkKey, clientNonce: cn, serverNonce: sn)
            sendCipher = FrameCipher(key: keys.clientToServer)
            start(receiving: FrameCipher(key: keys.serverToClient))
        } catch {
            channel.close()
            finish(nil)
            throw error
        }
    }

    static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a, b) { diff |= x ^ y }
        return diff == 0
    }

    private func start(receiving cipher: FrameCipher) {
        let channel = self.channel
        let continuation = self.continuation
        let heartbeat = self.heartbeat
        let backlog = self.backlog
        let sinkBox = self.sinkBox
        readTask = Task.detached { [weak self] in
            var cipher = cipher
            do {
                while !Task.isCancelled {
                    let head = try await channel.receive(exactly: 4)
                    guard let len32 = head.readU32(at: 0) else { throw FrameError.malformed("header") }
                    let len = Int(len32)
                    guard len >= FrameCipher.tagSize + 1, len <= FrameCipher.maxFrame else { throw FrameError.tooLarge(len) }
                    let sealed = try await channel.receive(exactly: len)
                    heartbeat.touch()
                    switch try SessionFrame.parse(try cipher.open(sealed)) {
                    case .json(let data):
                        if let message = try? IncomingMessage.decode(data) { continuation.yield(.message(message)) }
                    case .videoConfig(let sps, let pps):
                        sinkBox.value?.videoConfig(sps: sps, pps: pps)
                    case .videoUnit(let key, let avcc):
                        // No sink (a short session that never asked for video): drop without counting.
                        guard let sink = sinkBox.value, backlog.admit(bytes: avcc.count, keyframe: key) else { break }
                        let size = avcc.count
                        sink.videoUnit(avcc: avcc, keyframe: key) { backlog.consumed(bytes: size) }
                    case .ignored: break
                    }
                }
                await self?.finish("cancelled")
            } catch {
                await self?.finish("\(error)")
            }
        }
        liveness()
    }

    private let heartbeat = Heartbeat()

    /// Ping every 15 s; close after 45 s of silence.
    private func liveness() {
        let heartbeat = self.heartbeat
        pingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15))
                guard let self, !Task.isCancelled else { return }
                if heartbeat.silentFor > .seconds(45) { await self.finish("timed out"); return }
                try? await self.send(.ping)
            }
        }
    }

    public func send(_ message: OutgoingMessage) async throws {
        guard !finished, var cipher = sendCipher else { throw SessionError.notConnected }
        let plain = SessionFrame.encodeJSON(try message.encoded())
        let wire = try cipher.frame(plain)
        sendCipher = cipher
        channel.enqueue(wire) { [weak self] error in
            guard let error else { return }
            Task { await self?.finish("\(error)") }
        }
    }

    public func close() { finish(nil) }

    private func finish(_ reason: String?) {
        guard !finished else { return }
        finished = true
        readTask?.cancel()
        pingTask?.cancel()
        channel.close()
        continuation.yield(.closed(reason))
        continuation.finish()
    }
}
