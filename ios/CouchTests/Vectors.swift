import Foundation

/// Fixed protocol inputs and the outputs every implementation must agree on.
///
/// To add TV-side vectors: append a `ProtocolVector` to `ProtocolVector.all`. Every field is hex
/// except `phoneId` (UTF-8). `CryptoVectorTests` recomputes all of it for every entry, so a vector from
/// the Kotlin side only needs its own printed values pasted here.
struct ProtocolVector {
    var name: String
    var privateA: String   // phone (initiator) P-256 scalar
    var privateB: String   // TV (responder) P-256 scalar
    var publicA: String    // X.509 SubjectPublicKeyInfo DER
    var publicB: String
    var secret: String
    var commitA: String
    var sas: String
    var link: String
    var phoneId: String
    var clientNonce: String
    var serverNonce: String
    var phoneProof: String
    var tvProof: String
    var clientToServerKey: String
    var serverToClientKey: String
    /// Plaintext (hex) sealed as frame 0 and frame 1 with the c2s key.
    var plaintext: String
    var sealedFrame0: String
    var sealedFrame1: String

    /// Generated with Python `cryptography` + `hmac` from the spec formulas.
    static let reference = ProtocolVector(
        name: "reference (python)",
        privateA: String(repeating: "11", count: 32),
        privateB: String(repeating: "22", count: 32),
        publicA: "3059301306072a8648ce3d020106082a8648ce3d030107034200040217e617f0b6443928278f96999e69a23a4f2c152bdf6d6cdf66e5b80282d4ed194a7debcb97712d2dda3ca85aa8765a56f45fc758599652f2897c65306e5794",
        publicB: "3059301306072a8648ce3d020106082a8648ce3d03010703420004d65a93977caa3d1b081852ff57a79e465f1660577304baead505dd3a48589cf350185e895372df6221ea3a137557e473fddb6755f05bd507c3c533fce9c91285",
        secret: "ccfc261f58193c98ca4ad4a53bbac6f0ee29bc4d48438090446908622ca79af6",
        commitA: "76805a63a6980c18075424a13f32772d02d7c71b4965754c1ce84f5841220327",
        sas: "042431",
        link: "0cfe920b7981e9e93dfe67472a89b5e1c56d9ce303d377e0d0a9c53646ac59a4",
        phoneId: "phone-1234",
        clientNonce: "000102030405060708090a0b0c0d0e0f",
        serverNonce: "101112131415161718191a1b1c1d1e1f",
        phoneProof: "cada119313071a6493e3f7e6a475456f47f6990cd37f597f10d6dd160a269cff",
        tvProof: "2eca9077113586f2d146971c2e9d6a556d93caadfa84bca4f3b650ba28b2bb71",
        clientToServerKey: "603e070468640f3930dcc7386379631ffd2ce41ca7bc87445777a01e0632b090",
        serverToClientKey: "5d7eb159f7e89270c6252661b8d894b2a89d7752678587eddcff60ada8c14ed5",
        plaintext: "017b2274223a2270696e67227d",
        sealedFrame0: "d398b9034486831301b075cdd7cf3c8e2f7d0184f2629356b002985ad1",
        sealedFrame1: "c304dfce93eaf5812e13ea4d06246aa8e694da4fcec8594eb6f4e35ced"
    )

    static let all: [ProtocolVector] = [reference]
}

extension Data {
    init(hex: String) {
        var bytes: [UInt8] = []
        var chars = Array(hex)
        if chars.count % 2 == 1 { chars.insert("0", at: 0) }
        var i = 0
        while i < chars.count {
            if let b = UInt8(String(chars[i...i + 1]), radix: 16) { bytes.append(b) }
            i += 2
        }
        self.init(bytes)
    }

    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
