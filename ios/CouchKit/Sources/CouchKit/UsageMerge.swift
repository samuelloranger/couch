import Foundation

public struct MergedApp: Sendable, Equatable, Identifiable {
    public var id: String
    public var label: String
    public var packages: [String]
    /// Milliseconds per day over the last seven days, oldest first.
    public var perDayMs: [Int64]
    public var opens: Int
    public var lastUsed: Int64
    public var totalMs: Int64 { perDayMs.reduce(0, +) }
}

public enum UsageMerge {
    /// Days since 1970-01-01 for a `yyyy-MM-dd` string; nil when it isn't one.
    public static func dayNumber(_ text: String) -> Int? {
        let p = text.split(separator: "-")
        guard p.count == 3, let y = Int(p[0]), let m = Int(p[1]), let d = Int(p[2]), (1...12).contains(m), (1...31).contains(d) else { return nil }
        // Howard Hinnant's days_from_civil.
        let yy = m <= 2 ? y - 1 : y
        let era = (yy >= 0 ? yy : yy - 399) / 400
        let yoe = yy - era * 400
        let doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /// Just the app's own TV.
    public static func single(_ snapshot: UsageSnapshot, label: (String) -> String?) -> [MergedApp] {
        merge([snapshot], label: label)
    }

    /// Merges snapshots by package, then by any shared label (case-insensitive), aligning days on the newest `lastDay`.
    public static func merge(_ snapshots: [UsageSnapshot], label: (String) -> String?) -> [MergedApp] {
        struct Entry { var package: String; var row: AppUsageRow; var shift: Int }
        let newest = snapshots.compactMap { dayNumber($0.lastDay) }.max()
        var entries: [Entry] = []
        for s in snapshots {
            let shift = (newest != nil ? (dayNumber(s.lastDay).map { newest! - $0 } ?? 0) : 0)
            for row in s.apps { entries.append(Entry(package: row.packageName, row: row, shift: max(0, shift))) }
        }
        var parent = Array(0..<entries.count)
        func find(_ i: Int) -> Int { var i = i; while parent[i] != i { parent[i] = parent[parent[i]]; i = parent[i] }; return i }
        func union(_ a: Int, _ b: Int) { let (x, y) = (find(a), find(b)); if x != y { parent[y] = x } }
        var byKey: [String: Int] = [:]
        for (i, e) in entries.enumerated() {
            var keys = ["pkg:" + e.package]
            keys += e.row.labels.map { "label:" + $0.lowercased() }
            for k in keys {
                if let j = byKey[k] { union(i, j) } else { byKey[k] = i }
            }
        }
        var groups: [Int: [Entry]] = [:]
        for (i, e) in entries.enumerated() { groups[find(i), default: []].append(e) }
        var out: [MergedApp] = []
        for (_, group) in groups {
            var days = [Int64](repeating: 0, count: 7)
            for e in group {
                // An older snapshot's last day sits `shift` bars before the newest one.
                for (i, v) in e.row.perDayMs.enumerated() where i - e.shift >= 0 && i < 7 { days[i - e.shift] += v }
            }
            let packages = Array(Set(group.map(\.package))).sorted()
            let name = packages.lazy.compactMap(label).first
                ?? group.lazy.compactMap { $0.row.labels.first }.first
                ?? (packages.first?.split(separator: ".").last.map(String.init) ?? "")
            out.append(MergedApp(
                id: packages.joined(separator: "+"), label: name, packages: packages, perDayMs: days,
                opens: group.reduce(0) { $0 + $1.row.opens }, lastUsed: group.map(\.row.lastUsed).max() ?? 0))
        }
        return out.sorted { $0.totalMs != $1.totalMs ? $0.totalMs > $1.totalMs : $0.label < $1.label }
    }

    /// Totals per day across all apps.
    public static func dailyTotals(_ apps: [MergedApp]) -> [Int64] {
        (0..<7).map { i in apps.reduce(0) { $0 + $1.perDayMs[i] } }
    }
}
