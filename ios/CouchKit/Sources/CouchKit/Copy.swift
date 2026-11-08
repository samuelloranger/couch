import Foundation

/// What can move from one TV to another (spec section 8, "Copy").
public enum CopyGroup: String, Sendable, CaseIterable, Codable, Identifiable {
    case look, ambient, weather, apps, homeAssistant
    public var id: String { rawValue }

    public var keys: [String] {
        switch self {
        case .look: return ["accent", "clock24h"]
        case .ambient: return ["wallpaper", "subreddit", "wallpaperHours", "ambientMinutes"]
        case .weather: return ["weather", "weatherCity", "fahrenheit"]
        case .apps: return ["pinnedApps", "appSort", "manualAppOrder", "hiddenApps", "names"]
        case .homeAssistant: return ["homeAssistantUrl"]
        }
    }
}

public enum CopyPlan {
    /// Keys that only make sense on the TV they were made on; never sent to another one.
    public static let neverCopied: Set<String> = [
        "channels", "preferredChannels", "hiddenInputs", "inputIcons", "remoteInputKey", "remoteSettingsKey", "godmode", "hud",
    ]

    /// The `config.set` patch for `groups`, taken from `config`; missing and forbidden keys are skipped.
    public static func patch(from config: [String: JSONValue], groups: Set<CopyGroup>) -> [String: JSONValue] {
        var out: [String: JSONValue] = [:]
        for g in CopyGroup.allCases where groups.contains(g) {
            for k in g.keys where !neverCopied.contains(k) {
                if let v = config[k] { out[k] = v }
            }
        }
        return out
    }

    /// Strips forbidden keys from any patch (restoring an old backup included).
    public static func sanitized(_ patch: [String: JSONValue]) -> [String: JSONValue] {
        patch.filter { !neverCopied.contains($0.key) }
    }
}

public struct ConfigBackup: Sendable, Codable, Equatable, Identifiable {
    public var id: UUID
    public var name: String
    public var date: Date
    public var groups: [CopyGroup]
    public var patch: [String: JSONValue]

    public init(id: UUID = UUID(), name: String, date: Date = Date(), groups: [CopyGroup], patch: [String: JSONValue]) {
        self.id = id; self.name = name; self.date = date; self.groups = groups; self.patch = patch
    }
}

/// Named backups kept on the phone, as one JSON file.
public final class BackupStore: @unchecked Sendable {
    private let url: URL
    private let lock = NSLock()

    public init(url: URL) { self.url = url }

    public static func standard() -> BackupStore {
        let dir = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        return BackupStore(url: dir.appendingPathComponent("backups.json"))
    }

    public func load() -> [ConfigBackup] {
        lock.lock(); defer { lock.unlock() }
        guard let data = try? Data(contentsOf: url), let list = try? JSONDecoder().decode([ConfigBackup].self, from: data) else { return [] }
        return list.sorted { $0.date > $1.date }
    }

    @discardableResult
    public func add(_ backup: ConfigBackup) -> [ConfigBackup] {
        var list = load()
        list.append(backup)
        write(list)
        return load()
    }

    @discardableResult
    public func remove(id: UUID) -> [ConfigBackup] {
        var list = load()
        list.removeAll { $0.id == id }
        write(list)
        return load()
    }

    private func write(_ list: [ConfigBackup]) {
        lock.lock(); defer { lock.unlock() }
        if let data = try? JSONEncoder().encode(list) { try? data.write(to: url, options: .atomic) }
    }
}
