import Foundation
import Observation

/// A pairing the TV has answered: both screens show `code`.
public protocol PendingPairing: AnyObject, Sendable {
    var tvName: String { get }
    var code: String { get }
    func finish(matched: Bool) async throws -> PairedTV
    func cancel()
}

extension PairingInProgress: PendingPairing {}

public enum PairingFailure: Equatable, Sendable {
    case notPairing, unreachable, protocolError, declined, storage, dropped
}

/// One user-started pairing with one TV. It lives in the coordinator, not in a view, so a view that is
/// rebuilt (a Bonjour update, a sheet re-render) can never open a second connection: `start()` does
/// nothing unless the session is idle, and only `retry()` begins again.
@MainActor
@Observable
public final class PairingSession {
    public enum Phase: Equatable {
        case idle, connecting, compare(String), finishing, failed(PairingFailure), done
    }

    public private(set) var phase: Phase = .idle
    public private(set) var tvName: String?
    /// How many connections this session has opened (1 per explicit user action).
    public private(set) var attempts = 0

    @ObservationIgnored private let begin: @Sendable () async throws -> any PendingPairing
    @ObservationIgnored private var pending: (any PendingPairing)?
    @ObservationIgnored private var task: Task<Void, Never>?

    public init(begin: @escaping @Sendable () async throws -> any PendingPairing) { self.begin = begin }

    /// Idempotent: only the first call (or the first after a failure was retried) connects.
    public func start() {
        guard phase == .idle else { return }
        phase = .connecting
        attempts += 1
        let begin = self.begin
        task = Task { [weak self] in
            do {
                let p = try await begin()
                guard let self, !Task.isCancelled else { p.cancel(); return }
                self.pending = p
                self.tvName = p.tvName
                self.phase = .compare(p.code)
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                self?.phase = .failed(Self.failure(for: error))
            }
        }
    }

    /// Explicit "Try again": closes the previous connection first.
    public func retry() {
        reset()
        start()
    }

    public func cancel() { reset() }

    private func reset() {
        task?.cancel(); task = nil
        pending?.cancel(); pending = nil
        phase = .idle
    }

    /// Reports the user's answer; on a match the TV is handed to `save` (which may throw).
    public func finish(matched: Bool, save: @escaping @MainActor (PairedTV) throws -> Void) {
        guard case .compare = phase, let p = pending else { return }
        phase = .finishing
        task = Task { [weak self] in
            do {
                let tv = try await p.finish(matched: matched)
                do { try save(tv) } catch { self?.phase = .failed(.storage); return }
                self?.pending = nil
                self?.phase = .done
            } catch PairingError.declined {
                self?.pending = nil
                self?.phase = matched ? .failed(.declined) : .done
            } catch {
                self?.pending = nil
                self?.phase = .failed(.dropped)
            }
        }
    }

    static func failure(for error: Error) -> PairingFailure {
        guard let e = error as? PairingError else { return .unreachable }
        switch e {
        case .notPairing: return .notPairing
        case .unreachable: return .unreachable
        case .protocolError: return .protocolError
        case .declined: return .declined
        }
    }
}

/// Keeps pairing sessions alive across view rebuilds, one per TV key.
@MainActor
public final class PairingCoordinator {
    private var sessions: [String: PairingSession] = [:]

    public init() {}

    /// The existing session for `key`, or a new one built from `begin`; `begin` of an existing session is never replaced.
    public func session(key: String, begin: @escaping @Sendable () async throws -> any PendingPairing) -> PairingSession {
        if let s = sessions[key] { return s }
        let s = PairingSession(begin: begin)
        sessions[key] = s
        return s
    }

    public func cancelAll() {
        for s in sessions.values { s.cancel() }
        sessions.removeAll()
    }
}
