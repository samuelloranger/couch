import Foundation

/// A decrypted session frame, parsed. Anything malformed throws; nothing here traps on network data.
public enum SessionFrame: Sendable, Equatable {
    case json(Data)
    case videoConfig(sps: Data, pps: Data)
    case videoUnit(keyframe: Bool, avcc: Data)

    public static func parse(_ plain: Data) throws -> SessionFrame {
        guard let type = plain.first else { throw FrameError.malformed("empty frame") }
        let body = Data(plain.dropFirst())
        switch type {
        case 0x01:
            return .json(body)
        case 0x02:
            guard let spsLen = body.readU16(at: 0), body.count >= 2 + spsLen + 2,
                  let ppsLen = body.readU16(at: 2 + spsLen), body.count >= 4 + spsLen + ppsLen,
                  spsLen > 0, ppsLen > 0
            else { throw FrameError.malformed("video config") }
            let sps = body.subdata(in: 2..<(2 + spsLen))
            let pps = body.subdata(in: (4 + spsLen)..<(4 + spsLen + ppsLen))
            return .videoConfig(sps: sps, pps: pps)
        case 0x03:
            guard let flags = body.first else { throw FrameError.malformed("video unit") }
            let avcc = Data(body.dropFirst())
            guard AVCC.isWellFormed(avcc) else { throw FrameError.malformed("video unit NALs") }
            return .videoUnit(keyframe: flags & 0x01 != 0, avcc: avcc)
        default:
            throw FrameError.malformed("unknown frame type \(type)")
        }
    }

    public static func encodeJSON(_ json: Data) -> Data { Data([0x01]) + json }
}

public enum AVCC {
    /// True when the bytes are one or more complete `uint32 len | nal` units with no slack.
    public static func isWellFormed(_ data: Data) -> Bool {
        var offset = 0
        var units = 0
        while offset < data.count {
            guard let len = data.readU32(at: offset) else { return false }
            let end = offset + 4 + Int(len)
            guard len > 0, end <= data.count else { return false }
            offset = end
            units += 1
        }
        return units > 0
    }
}

/// Splits a byte stream of `uint32 BE length | payload` frames; bounded by `maxFrame`.
public struct LengthPrefixedReader: Sendable {
    public let maxFrame: Int
    private var buffer = Data()

    public init(maxFrame: Int) { self.maxFrame = maxFrame }

    public mutating func feed(_ data: Data) { buffer.append(data) }

    /// The next whole frame, nil when more bytes are needed; throws on a length out of range.
    public mutating func next() throws -> Data? {
        guard let len32 = buffer.readU32(at: 0) else { return nil }
        let len = Int(len32)
        guard len >= 1, len <= maxFrame else { throw FrameError.tooLarge(len) }
        guard buffer.count >= 4 + len else { return nil }
        let frame = buffer.subdata(in: (buffer.startIndex + 4)..<(buffer.startIndex + 4 + len))
        buffer = Data(buffer.dropFirst(4 + len))
        return frame
    }
}

/// Plain pre-session frames (pairing and the handshake): 4-byte length + JSON object.
public enum PlainWire {
    public static let maxFrame = 64 * 1024

    public static func encode(_ object: [String: JSONValue]) throws -> Data {
        let json = try JSONEncoder().encode(JSONValue.object(object))
        return Data.be32(UInt32(json.count)) + json
    }

    public static func decode(_ json: Data) throws -> [String: JSONValue] {
        guard let obj = try? JSONDecoder().decode(JSONValue.self, from: json), let o = obj.objectValue else {
            throw FrameError.malformed("not a JSON object")
        }
        return o
    }
}
