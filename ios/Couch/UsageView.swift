import Charts
import CouchKit
import SwiftUI

struct UsageView: View {
    let controller: TVController
    @State private var scope: Scope = .thisTV

    enum Scope: Hashable { case thisTV, allTVs }

    private var apps: [MergedApp] {
        guard let usage = controller.usage else { return [] }
        let label: (String) -> String? = { p in AppLayout.apps(controller.apps, config: controller.config).first { $0.packageName == p }?.label }
        return UsageMerge.merge(scope == .thisTV ? [usage.own] : [usage.own] + usage.peers, label: label)
    }

    private var lastDay: Int? {
        guard let usage = controller.usage else { return nil }
        let all = scope == .thisTV ? [usage.own] : [usage.own] + usage.peers
        return all.compactMap { UsageMerge.dayNumber($0.lastDay) }.max()
    }

    var body: some View {
        Group {
            if controller.usage == nil {
                let connected = controller.link == .connected
                let title: LocalizedStringKey = connected ? "Loading…" : "Not connected"
                let detail: LocalizedStringKey = connected ? "Asking the TV for its screen time." : "Connect to the TV to see its screen time."
                ContentUnavailableView {
                    Label(title, systemImage: "chart.bar")
                } description: {
                    Text(detail)
                }
            } else {
                content
            }
        }
        .refreshable { controller.refreshUsage() }
        .onAppear { controller.refreshUsage() }
    }

    private var content: some View {
        let apps = self.apps
        let totals = UsageMerge.dailyTotals(apps)
        let peersEmpty = controller.usage?.peers.isEmpty ?? true
        return ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Picker("Scope", selection: $scope) {
                    Text("This TV").tag(Scope.thisTV)
                    Text("All TVs").tag(Scope.allTVs)
                }
                .pickerStyle(.segmented)

                if scope == .allTVs && peersEmpty {
                    Text("No other TV has shared its screen time yet. Turn on Share screen time on each TV.")
                        .font(.footnote).foregroundStyle(.secondary)
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text("Last 7 days").font(.subheadline).foregroundStyle(.secondary)
                    Text(Format.duration(ms: totals.reduce(0, +))).font(.system(size: 38, weight: .semibold, design: .rounded))
                    Text("Today \(Format.duration(ms: totals.last ?? 0))").font(.footnote).foregroundStyle(.secondary)
                }

                Chart {
                    ForEach(Array(totals.enumerated()), id: \.offset) { i, ms in
                        BarMark(x: .value("Day", dayLabel(i)), y: .value("Minutes", Double(ms) / 60_000))
                            .foregroundStyle(i == totals.count - 1 ? Theme.accent : Theme.accent.opacity(0.45))
                            .cornerRadius(6)
                    }
                }
                .chartXScale(domain: (0..<7).map(dayLabel))
                .chartYAxis {
                    AxisMarks(position: .leading) { v in
                        AxisGridLine().foregroundStyle(.white.opacity(0.08))
                        AxisValueLabel { if let m = v.as(Double.self) { Text(Format.duration(ms: Int64(m * 60_000))).font(.caption2) } }
                    }
                }
                .frame(height: 180)
                .padding(16)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))

                VStack(spacing: 0) {
                    ForEach(apps.filter { $0.totalMs > 0 }) { app in
                        UsageAppRow(controller: controller, app: app, maxMs: apps.first?.totalMs ?? 1)
                        if app.id != apps.last(where: { $0.totalMs > 0 })?.id { Divider().padding(.leading, 62) }
                    }
                }
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
            .padding(16)
        }
    }

    /// Weekday initials, ending on the snapshot's last day.
    private func dayLabel(_ index: Int) -> String {
        guard let last = lastDay else { return "\(index)" }
        let day = last - (6 - index)
        let weekday = ((day % 7) + 7 + 4) % 7  // 1970-01-01 was a Thursday; 0 = Sunday
        let symbols = Calendar.current.shortWeekdaySymbols
        return symbols.indices.contains(weekday) ? symbols[weekday] : "\(index)"
    }
}

struct UsageAppRow: View {
    let controller: TVController
    let app: MergedApp
    let maxMs: Int64

    var body: some View {
        HStack(spacing: 12) {
            AppIconView(controller: controller, key: app.packages.first ?? app.id, label: app.label, size: 38)
            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Text(app.label).lineLimit(1)
                    Spacer()
                    Text(Format.duration(ms: app.totalMs)).foregroundStyle(.secondary).monospacedDigit()
                }
                GeometryReader { geo in
                    Capsule().fill(Theme.accent.opacity(0.85))
                        .frame(width: max(4, geo.size.width * CGFloat(Double(app.totalMs) / Double(max(maxMs, 1)))))
                }
                .frame(height: 5)
                Text("Opens: \(app.opens)").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}
