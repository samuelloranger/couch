import Foundation
import Network

public enum SessionEvent: Sendable {
    case message(IncomingMessage)
    case videoConfig(sps: Data, pps: Data)
    case videoUnit(keyframe: Bool, avcc: Data)
    case closed(String?)
}

final class Heartbeat: @unchecked Sendable {
    private let lock = NSLock()
    private var last = ContinuousClock.now
    func touch() { lock.lock(); last = .now; lock.unlock() }
    var silentFor: Duration { lock.lock(); defer { lock.unlock() }; return ContinuousClock.now - last }
}

public enum SessionError: Error, Equatable {
    case handshakeFailed(String)
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
            let reply = try await channel.receivePlain()
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
                    case .videoConfig(let sps, let pps): continuation.yield(.videoConfig(sps: sps, pps: pps))
                    case .videoUnit(let key, let avcc): continuation.yield(.videoUnit(keyframe: key, avcc: avcc))
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
