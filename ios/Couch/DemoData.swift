import CouchKit
import Foundation
import Network

/// Sample TVs for screenshots and design checks. Used only when the app launches with `COUCH_DEMO=1` in a debug build.
struct DemoData {
    var tv: PairedTV
    var state: TVState
    var config: [String: JSONValue]
    var groups: [SettingsGroup]
    var apps: [AppEntry]
    var inputs: [InputEntry]
    var usage: UsageReport

    static var discovered: [DiscoveredTV] {
        [
            DiscoveredTV(id: "demo-bedroom", name: "Bedroom TV", pairing: true, endpoint: .hostPort(host: "192.168.50.41", port: 47820)),
            DiscoveredTV(id: "demo-office", name: "Office", pairing: false, endpoint: .hostPort(host: "192.168.50.42", port: 47820)),
        ]
    }

    static var all: [DemoData] { [living, den] }

    private static let appRows: [(String, String, Bool, Bool)] = [
        ("com.netflix.ninja", "Netflix", true, false), ("com.google.android.youtube.tv", "YouTube", true, false),
        ("com.disney.disneyplus", "Disney+", true, false), ("com.plexapp.android", "Plex", true, false),
        ("tv.twitch.android.app", "Twitch", false, false), ("com.spotify.tv.android", "Spotify", false, false),
        ("com.amazon.amazonvideo.livingroom", "Prime Video", false, false), ("org.jellyfin.androidtv", "Jellyfin", false, false),
        ("com.android.vending", "Play Store", false, true), ("com.apple.atve.androidtv.appletv", "Apple TV", false, false),
    ]

    private static func snapshot(id: String, name: String, scale: Int64) -> UsageSnapshot {
        let base: [[Int64]] = [
            [95, 80, 120, 60, 140, 210, 150], [40, 55, 30, 25, 70, 90, 65], [20, 0, 45, 10, 30, 75, 20],
            [10, 15, 5, 40, 0, 35, 0], [0, 0, 20, 0, 5, 0, 25],
        ]
        let rows = zip(appRows, base).map { app, days in
            AppUsageRow(packageName: app.0, perDayMs: days.map { $0 * scale * 60_000 / 10 }, opens: Int(days.reduce(0, +) / 20) + 2,
                        lastUsed: 1_700_000_000_000, labels: [app.1])
        }
        return UsageSnapshot(deviceId: id, name: name, generatedAt: 1_700_000_000_000, lastDay: "2026-11-07", apps: rows)
    }

    static var living: DemoData {
        let usage = UsageReport(own: snapshot(id: "demo-living", name: "Living room", scale: 10), peers: [snapshot(id: "demo-den", name: "Den", scale: 4)])
        return DemoData(
            tv: PairedTV(id: "demo-living", name: "Living room", linkKey: Data(count: 32), lastHost: "192.168.50.40"),
            state: TVState(id: "demo-living", name: "Living room", model: "Chromecast with Google TV", android: "14", version: "1.8.0",
                           adb: .ready, foreground: "com.netflix.ninja", nowPlaying: NowPlaying(title: "Severance", artist: "Season 2", app: "Netflix")),
            config: [
                "accent": .string("mint"), "clock24h": .bool(true), "wallpaper": .string("daily"), "subreddit": .string("EarthPorn"),
                "ambientMinutes": .int(5), "weather": .bool(true), "weatherCity": .string("Québec"), "fahrenheit": .bool(false),
                "checkUpdates": .bool(true), "homeAssistantUrl": .string("http://homeassistant.local:8123"),
            ],
            groups: groups,
            apps: appRows.map { AppEntry(packageName: $0.0, label: $0.1, pinned: $0.2, hidden: $0.3, banner: false) },
            inputs: [
                InputEntry(key: "hdmi1", label: "Game console", icon: "game"), InputEntry(key: "hdmi2", label: "HDMI 2", icon: "hdmi"),
                InputEntry(key: "hdmi3", label: "Cable box", icon: "tv", hidden: true),
            ],
            usage: usage)
    }

    static var den: DemoData {
        var d = living
        d.tv = PairedTV(id: "demo-den", name: "Den", linkKey: Data(count: 32), lastHost: "192.168.50.43")
        d.state = TVState(id: "demo-den", name: "Den", model: "Fire TV Stick 4K", android: "9", version: "1.8.0", adb: .blocked)
        return d
    }

    static var groups: [SettingsGroup] {
        [
            SettingsGroup(id: "look", title: "Look", rows: [
                SettingRow(key: "accent", title: "Accent colour", help: "Used for focus and highlights.", kind: .swatches, options: [
                    SettingOption(value: .string("mint"), label: "Mint", color: "#34D399"),
                    SettingOption(value: .string("sky"), label: "Sky", color: "#38BDF8"),
                    SettingOption(value: .string("violet"), label: "Violet", color: "#A78BFA"),
                    SettingOption(value: .string("amber"), label: "Amber", color: "#FBBF24"),
                    SettingOption(value: .string("rose"), label: "Rose", color: "#FB7185"),
                ]),
                SettingRow(key: "clock24h", title: "24-hour clock", kind: .toggle),
            ]),
            SettingsGroup(id: "ambient", title: "Ambient", rows: [
                SettingRow(key: "wallpaper", title: "Ambient wallpaper", kind: .choice, options: [
                    SettingOption(value: .string("daily"), label: "Photo of the day"),
                    SettingOption(value: .string("reddit"), label: "From a subreddit"),
                ]),
                SettingRow(key: "subreddit", title: "Subreddit", kind: .text, visibleWhen: SettingCondition(key: "wallpaper", equals: .string("reddit"))),
                SettingRow(key: "ambientMinutes", title: "Ambient after", kind: .choice, options: [
                    SettingOption(value: .int(0), label: "Off"), SettingOption(value: .int(5), label: "5 min"), SettingOption(value: .int(15), label: "15 min"),
                ]),
            ]),
            SettingsGroup(id: "weather", title: "Weather", rows: [
                SettingRow(key: "weather", title: "Show weather", kind: .toggle),
                SettingRow(key: "weatherCity", title: "City", kind: .text),
                SettingRow(key: "fahrenheit", title: "Fahrenheit", kind: .toggle),
            ]),
            SettingsGroup(id: "system", title: "Updates", rows: [
                SettingRow(key: "checkUpdates", title: "Check for updates", help: "Looks for a new Couch when the TV wakes.", kind: .toggle),
            ]),
        ]
    }
}
