import CryptoKit
import Foundation

/// The primitives of the Couch protocol, byte for byte what the TV's `sync/Crypto.kt` does.
public enum CouchCrypto {
    /// HMAC-SHA256 over `parts`, each prefixed by its length as 2 bytes big-endian.
    public static func mac(_ key: Data, _ parts: Data...) -> Data { mac(key, parts: parts) }

    public static func mac(_ key: Data, parts: [Data]) -> Data {
        var h = HMAC<SHA256>(key: SymmetricKey(data: key))
        for part in parts {
            let n = part.count & 0xFFFF
            h.update(data: Data([UInt8(n >> 8), UInt8(n & 0xFF)]))
            h.update(data: part)
        }
        return Data(h.finalize())
    }

    public static func mac(_ key: Data, _ label: String, _ parts: Data...) -> Data {
        mac(key, parts: [Data(label.utf8)] + parts)
    }

    /// What the initiator promises before it learns the other side's key.
    public static func commit(_ publicKey: Data) -> Data { Data(SHA256.hash(data: publicKey)) }

    /// Six digits both screens show.
    public static func sas(secret: Data, initiator: Data, responder: Data) -> String {
        let m = mac(secret, "couch-sas", initiator, responder)
        let n = m.prefix(4).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        let digits = String(n % 1_000_000)
        return String(repeating: "0", count: 6 - digits.count) + digits
    }

    /// The long-lived key a paired TV and phone keep.
    public static func linkKey(secret: Data, initiator: Data, responder: Data) -> Data {
        mac(secret, "couch-key", initiator, responder)
    }

    public static func phoneProof(link: Data, clientNonce: Data, phoneId: String) -> Data {
        mac(link, "couch-phone", clientNonce, Data(phoneId.utf8))
    }

    public static func tvProof(link: Data, clientNonce: Data, serverNonce: Data) -> Data {
        mac(link, "couch-phone-ok", clientNonce, serverNonce)
    }

    public static func sessionKeys(link: Data, clientNonce: Data, serverNonce: Data) -> SessionKeys {
        SessionKeys(
            clientToServer: mac(link, "couch-phone-c2s", clientNonce, serverNonce),
            serverToClient: mac(link, "couch-phone-s2c", clientNonce, serverNonce)
        )
    }

    /// A fresh P-256 key pair and the DER (X.509 SubjectPublicKeyInfo) public key the wire carries.
    public static func makeKeyPair() -> (privateKey: P256.KeyAgreement.PrivateKey, publicDER: Data) {
        let k = P256.KeyAgreement.PrivateKey()
        return (k, k.publicKey.derRepresentation)
    }

    /// Raw 32-byte ECDH x-coordinate, as Java's `KeyAgreement.generateSecret`.
    public static func agree(_ privateKey: P256.KeyAgreement.PrivateKey, theirPublicDER: Data) throws -> Data {
        let theirs = try P256.KeyAgreement.PublicKey(derRepresentation: theirPublicDER)
        let secret = try privateKey.sharedSecretFromKeyAgreement(with: theirs)
        return secret.withUnsafeBytes { Data($0) }
    }

    public static func randomBytes(_ count: Int) -> Data {
        var g = SystemRandomNumberGenerator()
        return Data((0..<count).map { _ in UInt8.random(in: 0...255, using: &g) })
    }
}

public struct SessionKeys: Sendable, Equatable {
    public let clientToServer: Data
    public let serverToClient: Data
}

public enum FrameError: Error, Equatable {
    case tooLarge(Int)
    case tooShort(Int)
    case authenticationFailed
    case counterExhausted
    case malformed(String)
}

/// One direction of the encrypted session: AES-256-GCM, IV = 4 zero bytes + 8-byte big-endian counter,
/// empty AAD, output = ciphertext || 16-byte tag.
public struct FrameCipher: Sendable {
    public static let maxFrame = 4 * 1024 * 1024
    public static let tagSize = 16

    private let key: SymmetricKey
    public private(set) var counter: UInt64

    public init(key: Data, counter: UInt64 = 0) {
        self.key = SymmetricKey(data: key)
        self.counter = counter
    }

    private mutating func nextNonce() throws -> AES.GCM.Nonce {
        guard counter < UInt64.max else { throw FrameError.counterExhausted }
        var iv = Data(count: 4)
        withUnsafeBytes(of: counter.bigEndian) { iv.append(contentsOf: $0) }
        counter += 1
        return try AES.GCM.Nonce(data: iv)
    }

    /// Returns `ciphertext || tag` for one plaintext.
    public mutating func seal(_ plain: Data) throws -> Data {
        let box = try AES.GCM.seal(plain, using: key, nonce: try nextNonce())
        return box.ciphertext + box.tag
    }

    public mutating func open(_ sealed: Data) throws -> Data {
        guard sealed.count >= Self.tagSize else { throw FrameError.tooShort(sealed.count) }
        let nonce = try nextNonce()
        let body = Data(sealed)  // re-base indices
        let box = try AES.GCM.SealedBox(
            nonce: nonce,
            ciphertext: body.prefix(body.count - Self.tagSize),
            tag: body.suffix(Self.tagSize)
        )
        do { return try AES.GCM.open(box, using: key) } catch { throw FrameError.authenticationFailed }
    }

    /// The bytes to put on the wire: `uint32 BE length | ciphertext || tag`.
    public mutating func frame(_ plain: Data) throws -> Data {
        let sealed = try seal(plain)
        guard sealed.count <= Self.maxFrame else { throw FrameError.tooLarge(sealed.count) }
        return Data.be32(UInt32(sealed.count)) + sealed
    }
}

extension Data {
    static func be32(_ v: UInt32) -> Data { Data([UInt8(v >> 24), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)]) }

    /// Big-endian reads that never trap on short data.
    func readU32(at offset: Int) -> UInt32? {
        guard offset >= 0, offset + 4 <= count else { return nil }
        let s = startIndex + offset
        return (UInt32(self[s]) << 24) | (UInt32(self[s + 1]) << 16) | (UInt32(self[s + 2]) << 8) | UInt32(self[s + 3])
    }

    func readU16(at offset: Int) -> Int? {
        guard offset >= 0, offset + 2 <= count else { return nil }
        let s = startIndex + offset
        return (Int(self[s]) << 8) | Int(self[s + 1])
    }
}

public func b64(_ d: Data) -> String { d.base64EncodedString() }
public func unb64(_ s: String) -> Data? { Data(base64Encoded: s) }
