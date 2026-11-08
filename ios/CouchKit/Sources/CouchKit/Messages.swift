import Foundation

/// A message the TV sends (type 0x01 frames).
public enum IncomingMessage: Sendable, Equatable {
    case state(TVState)
    case config([String: JSONValue])
    case settings([SettingsGroup])
    case apps([AppEntry])
    case inputs([InputEntry])
    case usage(UsageReport)
    case icon(key: String, png: Data?)
    case streamStatus(StreamStatus)
    case error(about: String?, message: String)
    case pong
    case unknown(String)

    private struct Envelope: Decodable { var t: String }
    private struct ConfigBody: Decodable { var config: [String: JSONValue] }
    private struct SettingsBody: Decodable { var groups: [SettingsGroup] }
    private struct AppsBody: Decodable { var apps: [AppEntry] }
    private struct InputsBody: Decodable { var inputs: [InputEntry] }
    private struct IconBody: Decodable { var key: String; var png: String? }
    private struct ErrorBody: Decodable { var about: String?; var message: String? }

    /// Throws on text that is not a message with a string `t`; unknown types decode to `.unknown`.
    public static func decode(_ json: Data) throws -> IncomingMessage {
        let d = JSONDecoder()
        let t = try d.decode(Envelope.self, from: json).t
        switch t {
        case "state": return .state(try d.decode(TVState.self, from: json))
        case "config": return .config(try d.decode(ConfigBody.self, from: json).config)
        case "settings": return .settings(try d.decode(SettingsBody.self, from: json).groups)
        case "apps": return .apps(try d.decode(AppsBody.self, from: json).apps)
        case "inputs": return .inputs(try d.decode(InputsBody.self, from: json).inputs)
        case "usage": return .usage(try d.decode(UsageReport.self, from: json))
        case "icon":
            let b = try d.decode(IconBody.self, from: json)
            return .icon(key: b.key, png: b.png.flatMap { Data(base64Encoded: $0) })
        case "stream.status": return .streamStatus(try d.decode(StreamStatus.self, from: json))
        case "error":
            let b = try d.decode(ErrorBody.self, from: json)
            return .error(about: b.about, message: b.message ?? "")
        case "pong": return .pong
        default: return .unknown(t)
        }
    }
}

/// A message the phone sends.
public enum OutgoingMessage: Sendable, Equatable {
    case hello(app: String)
    case configSet([String: JSONValue])
    case key(RemoteKey, KeyAction)
    case text(String)
    case launch(packageName: String)
    case input(id: String)
    case usage
    case icon(key: String)
    case streamStart(StreamQuality)
    case streamStop
    case ping

    public static let maxText = 500

    /// Input and stream control: sent on their own path so bulk requests never delay them.
    public var isPriority: Bool {
        switch self {
        case .key, .text, .launch, .input, .streamStart, .streamStop, .ping: return true
        default: return false
        }
    }

    public var object: [String: JSONValue] {
        switch self {
        case .hello(let app): return ["t": .string("hello"), "app": .string(app)]
        case .configSet(let patch): return ["t": .string("config.set"), "patch": .object(patch)]
        case .key(let k, let a): return ["t": .string("key"), "key": .string(k.rawValue), "action": .string(a.rawValue)]
        case .text(let s): return ["t": .string("text"), "text": .string(String(s.prefix(Self.maxText)))]
        case .launch(let p): return ["t": .string("launch"), "package": .string(p)]
        case .input(let id): return ["t": .string("input"), "id": .string(id)]
        case .usage: return ["t": .string("usage")]
        case .icon(let k): return ["t": .string("icon"), "key": .string(k)]
        case .streamStart(let q): return ["t": .string("stream.start"), "quality": .string(q.rawValue)]
        case .streamStop: return ["t": .string("stream.stop")]
        case .ping: return ["t": .string("ping")]
        }
    }

    public func encoded() throws -> Data {
        try JSONEncoder().encode(JSONValue.object(object))
    }
}
