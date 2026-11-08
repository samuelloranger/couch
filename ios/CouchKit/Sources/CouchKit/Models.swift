import Foundation

public enum AdbStatus: String, Sendable, Codable {
    case ready, dialog, off, blocked, unknown

    public init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = AdbStatus(rawValue: raw) ?? .unknown
    }
}

public struct NowPlaying: Sendable, Codable, Equatable {
    public var title: String
    public var artist: String?
    public var app: String?
    public init(title: String, artist: String? = nil, app: String? = nil) {
        self.title = title; self.artist = artist; self.app = app
    }
}

public struct TVState: Sendable, Codable, Equatable {
    public var id: String
    public var name: String
    public var model: String?
    public var android: String?
    public var version: String?
    public var mac: String?
    public var adb: AdbStatus
    public var foreground: String?
    public var nowPlaying: NowPlaying?

    public init(id: String, name: String, model: String? = nil, android: String? = nil, version: String? = nil,
                mac: String? = nil, adb: AdbStatus = .unknown, foreground: String? = nil, nowPlaying: NowPlaying? = nil) {
        self.id = id; self.name = name; self.model = model; self.android = android; self.version = version
        self.mac = mac; self.adb = adb; self.foreground = foreground; self.nowPlaying = nowPlaying
    }

    enum CodingKeys: String, CodingKey { case id, name, model, android, version, mac, adb, foreground, nowPlaying }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        model = try c.decodeIfPresent(String.self, forKey: .model)
        android = try c.decodeIfPresent(String.self, forKey: .android)
        version = try c.decodeIfPresent(String.self, forKey: .version)
        mac = try c.decodeIfPresent(String.self, forKey: .mac)
        adb = try c.decodeIfPresent(AdbStatus.self, forKey: .adb) ?? .unknown
        foreground = try c.decodeIfPresent(String.self, forKey: .foreground)
        nowPlaying = try c.decodeIfPresent(NowPlaying.self, forKey: .nowPlaying)
    }
}

public struct AppEntry: Sendable, Codable, Equatable, Identifiable {
    public var packageName: String
    public var label: String
    public var pinned: Bool
    public var hidden: Bool
    public var banner: Bool
    public var id: String { packageName }

    public init(packageName: String, label: String, pinned: Bool = false, hidden: Bool = false, banner: Bool = false) {
        self.packageName = packageName; self.label = label; self.pinned = pinned; self.hidden = hidden; self.banner = banner
    }

    enum CodingKeys: String, CodingKey { case packageName = "package", label, pinned, hidden, banner }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        packageName = try c.decode(String.self, forKey: .packageName)
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? packageName
        pinned = try c.decodeIfPresent(Bool.self, forKey: .pinned) ?? false
        hidden = try c.decodeIfPresent(Bool.self, forKey: .hidden) ?? false
        banner = try c.decodeIfPresent(Bool.self, forKey: .banner) ?? false
    }
}

public struct InputEntry: Sendable, Codable, Equatable, Identifiable {
    public var key: String
    public var label: String
    public var icon: String?
    public var hidden: Bool
    public var id: String { key }

    public init(key: String, label: String, icon: String? = nil, hidden: Bool = false) {
        self.key = key; self.label = label; self.icon = icon; self.hidden = hidden
    }

    enum CodingKeys: String, CodingKey { case key, label, icon, hidden }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .key)
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? key
        icon = try c.decodeIfPresent(String.self, forKey: .icon)
        hidden = try c.decodeIfPresent(Bool.self, forKey: .hidden) ?? false
    }
}

public struct StreamStatus: Sendable, Codable, Equatable {
    public enum State: String, Sendable, Codable {
        case starting, live, needsAdb = "needs-adb", ended, error

        public init(from decoder: Decoder) throws {
            let raw = try decoder.singleValueContainer().decode(String.self)
            self = State(rawValue: raw) ?? .error
        }
    }
    public var state: State
    public var width: Int?
    public var height: Int?
    public var message: String?
    public init(state: State, width: Int? = nil, height: Int? = nil, message: String? = nil) {
        self.state = state; self.width = width; self.height = height; self.message = message
    }
}

public enum StreamQuality: String, Sendable, CaseIterable, Codable {
    case high, medium, low
}

public enum RemoteKey: String, Sendable, CaseIterable {
    case up = "UP", down = "DOWN", left = "LEFT", right = "RIGHT", ok = "OK", back = "BACK", home = "HOME"
    case menu = "MENU", playPause = "PLAY_PAUSE", rewind = "REWIND", fastForward = "FAST_FORWARD"
    case volumeUp = "VOLUME_UP", volumeDown = "VOLUME_DOWN", mute = "MUTE", power = "POWER"
    case input = "INPUT", settings = "SETTINGS", channelUp = "CHANNEL_UP", channelDown = "CHANNEL_DOWN"
}

public enum KeyAction: String, Sendable { case tap, down, up }

// MARK: - Settings schema

public struct SettingOption: Sendable, Codable, Equatable, Identifiable {
    public var value: JSONValue
    public var label: String
    public var color: String?
    public var id: JSONValue { value }
    public init(value: JSONValue, label: String, color: String? = nil) {
        self.value = value; self.label = label; self.color = color
    }
}

public struct SettingCondition: Sendable, Codable, Equatable {
    public var key: String
    public var equals: JSONValue
    public init(key: String, equals: JSONValue) { self.key = key; self.equals = equals }
}

public struct SettingRow: Sendable, Codable, Equatable, Identifiable {
    public enum Kind: String, Sendable, Codable {
        case toggle, choice, text, swatches, unknown

        public init(from decoder: Decoder) throws {
            let raw = try decoder.singleValueContainer().decode(String.self)
            self = Kind(rawValue: raw) ?? .unknown
        }
    }
    public var key: String
    public var title: String
    public var help: String?
    public var kind: Kind
    public var options: [SettingOption]
    public var visibleWhen: SettingCondition?
    public var id: String { key }

    public init(key: String, title: String, help: String? = nil, kind: Kind, options: [SettingOption] = [],
                visibleWhen: SettingCondition? = nil) {
        self.key = key; self.title = title; self.help = help; self.kind = kind; self.options = options
        self.visibleWhen = visibleWhen
    }

    enum CodingKeys: String, CodingKey { case key, title, help, kind, options, visibleWhen }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .key)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? key
        help = try c.decodeIfPresent(String.self, forKey: .help)
        kind = try c.decodeIfPresent(Kind.self, forKey: .kind) ?? .unknown
        options = try c.decodeIfPresent([SettingOption].self, forKey: .options) ?? []
        visibleWhen = try c.decodeIfPresent(SettingCondition.self, forKey: .visibleWhen)
    }

    /// Whether the row shows, given the TV's current config.
    public func isVisible(in config: [String: JSONValue]) -> Bool {
        guard let cond = visibleWhen else { return true }
        guard let current = config[cond.key] else { return false }
        return current.matches(cond.equals)
    }
}

public struct SettingsGroup: Sendable, Codable, Equatable, Identifiable {
    public var id: String
    public var title: String
    public var rows: [SettingRow]
    public init(id: String, title: String, rows: [SettingRow]) { self.id = id; self.title = title; self.rows = rows }

    enum CodingKeys: String, CodingKey { case id, title, rows }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? id
        rows = try c.decodeIfPresent([SettingRow].self, forKey: .rows) ?? []
    }
}

// MARK: - Usage

public struct AppUsageRow: Sendable, Equatable {
    public var packageName: String
    public var perDayMs: [Int64]
    public var opens: Int
    public var lastUsed: Int64
    public var labels: [String]

    public init(packageName: String, perDayMs: [Int64], opens: Int, lastUsed: Int64, labels: [String] = []) {
        self.packageName = packageName; self.perDayMs = perDayMs; self.opens = opens; self.lastUsed = lastUsed; self.labels = labels
    }
}

/// A TV's `Snapshot.toJson()` object. `apps` is a map keyed by package on the TV today; an array of
/// rows with a `package` field is accepted too.
public struct UsageSnapshot: Sendable, Decodable, Equatable {
    public var version: Int
    public var deviceId: String
    public var name: String
    public var generatedAt: Int64
    public var lastDay: String
    public var apps: [AppUsageRow]

    public init(version: Int = 1, deviceId: String, name: String, generatedAt: Int64, lastDay: String, apps: [AppUsageRow]) {
        self.version = version; self.deviceId = deviceId; self.name = name
        self.generatedAt = generatedAt; self.lastDay = lastDay; self.apps = apps
    }

    private struct RowBody: Decodable {
        var packageName: String?
        var perDayMs: [Int64]?
        var opens: Int?
        var lastUsed: Int64?
        var labels: [String]?
        enum CodingKeys: String, CodingKey { case packageName = "package", perDayMs, opens, lastUsed, labels }
    }

    enum CodingKeys: String, CodingKey { case version, deviceId, name, generatedAt, lastDay, apps }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 1
        deviceId = try c.decodeIfPresent(String.self, forKey: .deviceId) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        generatedAt = try c.decodeIfPresent(Int64.self, forKey: .generatedAt) ?? 0
        lastDay = try c.decodeIfPresent(String.self, forKey: .lastDay) ?? ""
        func row(_ pkg: String, _ b: RowBody) -> AppUsageRow {
            var days = (b.perDayMs ?? []).prefix(7).map { max(0, $0) }
            if days.count < 7 { days = Array(repeating: 0, count: 7 - days.count) + days }
            return AppUsageRow(packageName: pkg, perDayMs: Array(days), opens: b.opens ?? 0, lastUsed: b.lastUsed ?? 0, labels: b.labels ?? [])
        }
        if let map = try? c.decode([String: RowBody].self, forKey: .apps) {
            apps = map.map { row($0.key, $0.value) }.sorted { $0.packageName < $1.packageName }
        } else if let list = try? c.decode([RowBody].self, forKey: .apps) {
            apps = list.compactMap { b in b.packageName.map { row($0, b) } }
        } else {
            apps = []
        }
    }
}

public struct UsageReport: Sendable, Decodable, Equatable {
    public var own: UsageSnapshot
    public var peers: [UsageSnapshot]
    public init(own: UsageSnapshot, peers: [UsageSnapshot] = []) { self.own = own; self.peers = peers }

    enum CodingKeys: String, CodingKey { case own, peers }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        own = try c.decode(UsageSnapshot.self, forKey: .own)
        peers = try c.decodeIfPresent([UsageSnapshot].self, forKey: .peers) ?? []
    }
}
