import Foundation

/// Any JSON value. Config values travel as these so the phone never has to know every key.
public enum JSONValue: Sendable, Equatable, Hashable, Codable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Int.self) { self = .int(v) }
        else if let v = try? c.decode(Double.self) { self = .double(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([JSONValue].self) { self = .array(v) }
        else if let v = try? c.decode([String: JSONValue].self) { self = .object(v) }
        else { throw DecodingError.dataCorruptedError(in: c, debugDescription: "unsupported JSON value") }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .int(let v): try c.encode(v)
        case .double(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }

    public var stringValue: String? { if case .string(let v) = self { return v } else { return nil } }
    public var boolValue: Bool? { if case .bool(let v) = self { return v } else { return nil } }
    public var intValue: Int? {
        switch self {
        case .int(let v): return v
        case .double(let v): return v == v.rounded() && abs(v) < 1e15 ? Int(v) : nil
        default: return nil
        }
    }
    public var arrayValue: [JSONValue]? { if case .array(let v) = self { return v } else { return nil } }
    public var objectValue: [String: JSONValue]? { if case .object(let v) = self { return v } else { return nil } }
    public var stringArray: [String] { arrayValue?.compactMap(\.stringValue) ?? [] }
    public var stringMap: [String: String] { (objectValue ?? [:]).compactMapValues(\.stringValue) }

    /// Equality that treats 5 and 5.0 as the same option value.
    public func matches(_ other: JSONValue) -> Bool {
        if self == other { return true }
        if let a = intValue, let b = other.intValue { return a == b }
        return false
    }
}
