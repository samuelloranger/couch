import CryptoKit
import XCTest
@testable import CouchKit

final class CryptoVectorTests: XCTestCase {
    func testKeysAndSecret() throws {
        for v in ProtocolVector.all {
            let a = try P256.KeyAgreement.PrivateKey(rawRepresentation: Data(hex: v.privateA))
            let b = try P256.KeyAgreement.PrivateKey(rawRepresentation: Data(hex: v.privateB))
            XCTAssertEqual(a.publicKey.derRepresentation.hex, v.publicA, v.name)
            XCTAssertEqual(b.publicKey.derRepresentation.hex, v.publicB, v.name)
            let s1 = try CouchCrypto.agree(a, theirPublicDER: Data(hex: v.publicB))
            let s2 = try CouchCrypto.agree(b, theirPublicDER: Data(hex: v.publicA))
            XCTAssertEqual(s1.hex, v.secret, v.name)
            XCTAssertEqual(s2.hex, v.secret, v.name)
            XCTAssertEqual(CouchCrypto.commit(Data(hex: v.publicA)).hex, v.commitA, v.name)
        }
    }

    func testSasAndLinkKey() {
        for v in ProtocolVector.all {
            let secret = Data(hex: v.secret), a = Data(hex: v.publicA), b = Data(hex: v.publicB)
            XCTAssertEqual(CouchCrypto.sas(secret: secret, initiator: a, responder: b), v.sas, v.name)
            XCTAssertEqual(CouchCrypto.linkKey(secret: secret, initiator: a, responder: b).hex, v.link, v.name)
        }
    }

    func testHandshakeProofsAndSessionKeys() {
        for v in ProtocolVector.all {
            let link = Data(hex: v.link), cn = Data(hex: v.clientNonce), sn = Data(hex: v.serverNonce)
            XCTAssertEqual(CouchCrypto.phoneProof(link: link, clientNonce: cn, phoneId: v.phoneId).hex, v.phoneProof, v.name)
            XCTAssertEqual(CouchCrypto.tvProof(link: link, clientNonce: cn, serverNonce: sn).hex, v.tvProof, v.name)
            let keys = CouchCrypto.sessionKeys(link: link, clientNonce: cn, serverNonce: sn)
            XCTAssertEqual(keys.clientToServer.hex, v.clientToServerKey, v.name)
            XCTAssertEqual(keys.serverToClient.hex, v.serverToClientKey, v.name)
        }
    }

    func testFrameSealingMatchesReference() throws {
        for v in ProtocolVector.all {
            var cipher = FrameCipher(key: Data(hex: v.clientToServerKey))
            let plain = Data(hex: v.plaintext)
            XCTAssertEqual(try cipher.seal(plain).hex, v.sealedFrame0, v.name)
            XCTAssertEqual(try cipher.seal(plain).hex, v.sealedFrame1, v.name)
            var opener = FrameCipher(key: Data(hex: v.clientToServerKey))
            XCTAssertEqual(try opener.open(Data(hex: v.sealedFrame0)), plain, v.name)
            XCTAssertEqual(try opener.open(Data(hex: v.sealedFrame1)), plain, v.name)
        }
    }

    func testMacLengthPrefixPreventsSplitCollisions() {
        let key = Data(repeating: 7, count: 32)
        XCTAssertNotEqual(CouchCrypto.mac(key, Data([1, 2]), Data([3])), CouchCrypto.mac(key, Data([1]), Data([2, 3])))
    }

    func testTamperedAndOutOfOrderFramesFail() throws {
        let v = ProtocolVector.reference
        var good = Data(hex: v.sealedFrame0)
        good[0] ^= 0x01
        var opener = FrameCipher(key: Data(hex: v.clientToServerKey))
        XCTAssertThrowsError(try opener.open(good))
        // A replayed frame 0 where counter 1 is expected must not verify.
        var replay = FrameCipher(key: Data(hex: v.clientToServerKey), counter: 1)
        XCTAssertThrowsError(try replay.open(Data(hex: v.sealedFrame0)))
        var short = FrameCipher(key: Data(hex: v.clientToServerKey))
        XCTAssertThrowsError(try short.open(Data([1, 2, 3])))
    }

    func testWireFrameHasLengthPrefix() throws {
        let v = ProtocolVector.reference
        var cipher = FrameCipher(key: Data(hex: v.clientToServerKey))
        let wire = try cipher.frame(Data(hex: v.plaintext))
        XCTAssertEqual(wire.readU32(at: 0), UInt32(Data(hex: v.sealedFrame0).count))
        XCTAssertEqual(wire.dropFirst(4).hex, v.sealedFrame0)
    }

    func testSasIsZeroPadded() {
        // Any secret works: the format is what is checked.
        for i in 0..<50 {
            let s = CouchCrypto.sas(secret: Data(repeating: UInt8(i), count: 32), initiator: Data([1]), responder: Data([2]))
            XCTAssertEqual(s.count, 6)
            XCTAssertTrue(s.allSatisfy(\.isNumber))
        }
    }
}

/// Vectors produced by the TV side (JVM test, cross-checked with an independent Python run).
final class TVSideVectorTests: XCTestCase {
    private let link = Data((0..<32).map { UInt8($0) })
    private let cn = Data((0..<16).map { UInt8($0) })
    private let sn = Data((16..<32).map { UInt8($0) })
    private let phoneId = "phone-1"
    private let ping = Data(hex: "017b2274223a2270696e67227d")

    func testProofsAndKeys() {
        XCTAssertEqual(CouchCrypto.phoneProof(link: link, clientNonce: cn, phoneId: phoneId).hex,
                       "b899ace203f477d84935294866679fb4fdd558d23d0364d2c7f0892c9bf9335e")
        XCTAssertEqual(CouchCrypto.tvProof(link: link, clientNonce: cn, serverNonce: sn).hex,
                       "85974a22f380e4af22b49c348e431d05676fa6241a4d85a0f528ca910a334e08")
        let keys = CouchCrypto.sessionKeys(link: link, clientNonce: cn, serverNonce: sn)
        XCTAssertEqual(keys.clientToServer.hex, "696ef403b13b52fdcf78f03c30c9b290cf5902e8b81c075464000c4a60d051ce")
        XCTAssertEqual(keys.serverToClient.hex, "752b2efa19d5cd52d8ff345f3f0e03d704eedc7beee1ebe2dbc89f60b0ac0fc0")
    }

    func testSealedFrames() throws {
        let keys = CouchCrypto.sessionKeys(link: link, clientNonce: cn, serverNonce: sn)
        var c2s = FrameCipher(key: keys.clientToServer)
        XCTAssertEqual(try c2s.seal(ping).hex, "0a0a6b0bbb0b4529b51e65b86f44dfce87c8b49dae2ec0d91888872dc1")
        var s2c = FrameCipher(key: keys.serverToClient)
        XCTAssertEqual(try s2c.seal(ping).hex, "c39f28299cb0f69dfee7b8e6acee1c1ba89ffba40c26f9d2f9381edf80")
        XCTAssertEqual(try s2c.seal(ping).hex, "4ae581822bffc631240d4ef352371933d47865ec20a024ff3c7aa01aaa")
        // And the phone opens what the TV sealed, in order.
        var opener = FrameCipher(key: keys.serverToClient)
        XCTAssertEqual(try opener.open(Data(hex: "c39f28299cb0f69dfee7b8e6acee1c1ba89ffba40c26f9d2f9381edf80")), ping)
        XCTAssertEqual(try opener.open(Data(hex: "4ae581822bffc631240d4ef352371933d47865ec20a024ff3c7aa01aaa")), ping)
        var framed = FrameCipher(key: keys.serverToClient)
        XCTAssertEqual(try framed.frame(ping).prefix(4).hex, "0000001d")
    }
}
