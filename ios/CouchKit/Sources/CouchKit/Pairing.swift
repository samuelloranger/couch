import CryptoKit
import Foundation
import Network

public enum PairingError: Error, Equatable {
    case notPairing
    case unreachable(String)
    case protocolError(String)
    case declined
}

/// A pairing in flight: the TV has answered and both screens show `code`.
public final class PairingInProgress: @unchecked Sendable {
    public let tvId: String
    public let tvName: String
    public let code: String
    private let channel: TCPChannel
    private let phonePublic: Data
    private let tvPublic: Data
    private let secret: Data
    private let host: String?

    init(tvId: String, tvName: String, code: String, channel: TCPChannel, phonePublic: Data, tvPublic: Data, secret: Data, host: String?) {
        self.tvId = tvId; self.tvName = tvName; self.code = code; self.channel = channel
        self.phonePublic = phonePublic; self.tvPublic = tvPublic; self.secret = secret; self.host = host
    }

    /// Tells the TV whether the digits matched; on a match, returns the TV to store.
    public func finish(matched: Bool) async throws -> PairedTV {
        defer { channel.close() }
        do {
            try await channel.sendPlain(["ok": .bool(matched)])
            guard matched else { throw PairingError.declined }
            let reply = try await channel.receivePlain()
            guard reply["done"]?.boolValue == true else { throw PairingError.declined }
        } catch let e as PairingError {
            throw e
        } catch {
            throw PairingError.unreachable("\(error)")
        }
        return PairedTV(
            id: tvId, name: tvName,
            linkKey: CouchCrypto.linkKey(secret: secret, initiator: phonePublic, responder: tvPublic),
            lastHost: host ?? channel.remoteIPv4, mac: nil
        )
    }

    public func cancel() { channel.close() }
}

public enum PairingClient {
    public static let port: UInt16 = 47820

    public static func begin(host: String, port: UInt16 = PairingClient.port, phoneId: String, phoneName: String) async throws -> PairingInProgress {
        try await begin(channel: TCPChannel(host: host, port: port), host: host, phoneId: phoneId, phoneName: phoneName)
    }

    public static func begin(endpoint: NWEndpoint, phoneId: String, phoneName: String) async throws -> PairingInProgress {
        try await begin(channel: TCPChannel(endpoint: endpoint), host: nil, phoneId: phoneId, phoneName: phoneName)
    }

    private static func begin(channel: TCPChannel, host: String?, phoneId: String, phoneName: String) async throws -> PairingInProgress {
        do {
            try await channel.open()
            let keys = CouchCrypto.makeKeyPair()
            try await channel.sendPlain([
                "op": .string("pair1"), "id": .string(phoneId), "name": .string(phoneName),
                "commit": .string(b64(CouchCrypto.commit(keys.publicDER))), "kind": .string("phone"),
            ])
            let reply = try await channel.receivePlain()
            if reply["error"] != nil { throw PairingError.notPairing }
            guard let pubText = reply["pub"]?.stringValue, let tvPub = unb64(pubText),
                  let tvId = reply["id"]?.stringValue, !tvId.isEmpty, tvId != phoneId
            else { throw PairingError.protocolError("bad reply") }
            let tvName = String((reply["name"]?.stringValue ?? tvId).prefix(80))
            try await channel.sendPlain(["pub": .string(b64(keys.publicDER))])
            let secret: Data
            do { secret = try CouchCrypto.agree(keys.privateKey, theirPublicDER: tvPub) }
            catch { throw PairingError.protocolError("bad key") }
            let code = CouchCrypto.sas(secret: secret, initiator: keys.publicDER, responder: tvPub)
            return PairingInProgress(tvId: tvId, tvName: tvName, code: code, channel: channel,
                                     phonePublic: keys.publicDER, tvPublic: tvPub, secret: secret, host: host)
        } catch let e as PairingError {
            channel.close(); throw e
        } catch {
            channel.close(); throw PairingError.unreachable("\(error)")
        }
    }
}
