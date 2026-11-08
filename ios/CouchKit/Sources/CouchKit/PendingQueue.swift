import Foundation

/// Input waiting for a connection: at most `capacity` entries, each good for `lifetime` (on a monotonic clock,
/// so a wall-clock change cannot revive or kill an entry).
/// Only messages that make sense a moment late (keys, text, launch, input switch) are accepted.
public struct PendingQueue: Sendable {
    public static let capacity = 8
    public static let lifetime: Duration = .seconds(5)

    private var entries: [(message: OutgoingMessage, at: ContinuousClock.Instant)] = []

    public init() {}

    public var count: Int { entries.count }

    /// False (and nothing stored) for stream control and bulk requests.
    @discardableResult
    public mutating func append(_ message: OutgoingMessage, now: ContinuousClock.Instant = .now) -> Bool {
        guard message.isQueueable else { return false }
        dropExpired(now: now)
        if entries.count >= Self.capacity { entries.removeFirst() }
        entries.append((message, now))
        return true
    }

    /// Everything still fresh, oldest first; the queue is empty afterwards.
    public mutating func drain(now: ContinuousClock.Instant = .now) -> [OutgoingMessage] {
        dropExpired(now: now)
        defer { entries.removeAll() }
        return entries.map(\.message)
    }

    /// How many fresh entries are waiting (expired ones are dropped first).
    public mutating func liveCount(now: ContinuousClock.Instant = .now) -> Int {
        dropExpired(now: now)
        return entries.count
    }

    public mutating func clear() { entries.removeAll() }

    private mutating func dropExpired(now: ContinuousClock.Instant) {
        entries.removeAll { now - $0.at > Self.lifetime }
    }
}

extension OutgoingMessage {
    /// Worth sending a few seconds late: what the person pressed, never stream control or bulk traffic.
    public var isQueueable: Bool {
        switch self {
        case .key, .text, .launch, .input: return true
        default: return false
        }
    }
}
