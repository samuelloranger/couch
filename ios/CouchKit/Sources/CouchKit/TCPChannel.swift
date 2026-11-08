import Foundation
import Network

public enum ChannelError: Error, Equatable {
    case timedOut
    case closed
    case failed(String)
}

private final class OneShot: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func claim() -> Bool { lock.lock(); defer { lock.unlock() }; if done { return false }; done = true; return true }
}

/// A TCP connection with async, bounded reads. Wraps `NWConnection`; the only place that touches it.
public final class TCPChannel: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "cloud.samlo.couch.channel")

    public init(host: String, port: UInt16) {
        let params = NWParameters.tcp
        if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcp.noDelay = true
            tcp.enableKeepalive = true
            tcp.keepaliveIdle = 20
        }
        connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port) ?? 47820, using: params)
    }

    public init(endpoint: NWEndpoint) {
        let params = NWParameters.tcp
        if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcp.noDelay = true
            tcp.enableKeepalive = true
            tcp.keepaliveIdle = 20
        }
        connection = NWConnection(to: endpoint, using: params)
    }

    /// The IPv4 address this channel ended up talking to, for remembering.
    public var remoteIPv4: String? {
        guard let remote = connection.currentPath?.remoteEndpoint, case .hostPort(let host, _) = remote,
              case .ipv4(let addr) = host else { return nil }
        let text = "\(addr)"
        return text.split(separator: "%").first.map(String.init)
    }

    public func open(timeout: TimeInterval = 8) async throws {
        let shot = OneShot()
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if shot.claim() { cont.resume() }
                case .failed(let e):
                    if shot.claim() { cont.resume(throwing: ChannelError.failed("\(e)")) }
                case .cancelled:
                    if shot.claim() { cont.resume(throwing: ChannelError.closed) }
                default: break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) { [connection] in
                if shot.claim() {
                    cont.resume(throwing: ChannelError.timedOut)
                    connection.cancel()
                }
            }
        }
        connection.stateUpdateHandler = nil
    }

    /// Hands `data` to the network synchronously, so call order is wire order even from an actor.
    public func enqueue(_ data: Data, completion: @escaping @Sendable (Error?) -> Void) {
        connection.send(content: data, completion: .contentProcessed { completion($0) })
    }

    /// Queues `data` immediately (call order is wire order) and returns once it is handed to the network.
    public func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { cont.resume(throwing: ChannelError.failed("\(error)")) } else { cont.resume() }
            })
        }
    }

    /// Exactly `count` bytes; throws if the peer closes first. `count` must be small enough to allocate.
    public func receive(exactly count: Int) async throws -> Data {
        guard count > 0, count <= FrameCipher.maxFrame + 64 else { throw FrameError.tooLarge(count) }
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, error in
                if let error {
                    cont.resume(throwing: ChannelError.failed("\(error)"))
                } else if let data, data.count == count {
                    cont.resume(returning: data)
                } else {
                    cont.resume(throwing: ChannelError.closed)
                }
            }
        }
    }

    /// Reads one plain `uint32 length | JSON` frame, bounded by `max`.
    public func receivePlain(max: Int = PlainWire.maxFrame) async throws -> [String: JSONValue] {
        let head = try await receive(exactly: 4)
        guard let len = head.readU32(at: 0), len >= 1, Int(len) <= max else { throw FrameError.tooLarge(Int(head.readU32(at: 0) ?? 0)) }
        return try PlainWire.decode(try await receive(exactly: Int(len)))
    }

    public func sendPlain(_ object: [String: JSONValue]) async throws {
        try await send(try PlainWire.encode(object))
    }

    public func close() {
        connection.stateUpdateHandler = nil
        connection.cancel()
    }
}
