import CouchKit
import SwiftUI

/// Apps and inputs as the TV currently shows them: the config is the truth, so edits show at once.
struct AppsView: View {
    let controller: TVController
    @State private var editing = false
    @State private var renaming: Renaming?
    @State private var renameText = ""

    struct Renaming: Identifiable {
        let key: String
        let current: String
        var id: String { key }
    }

    var body: some View {
        Group {
            if controller.apps.isEmpty && controller.inputs.isEmpty {
                let connected = controller.link == .connected
                let title: LocalizedStringKey = connected ? "No apps yet" : "Not connected"
                let detail: LocalizedStringKey = connected ? "The TV hasn't sent its apps." : "Connect to the TV to see its apps."
                ContentUnavailableView {
                    Label(title, systemImage: "square.grid.2x2")
                } description: {
                    Text(detail)
                }
            } else if editing {
                editor
            } else {
                grid
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(editing ? "Done" : "Edit") { withAnimation(.snappy) { editing.toggle() } }
                    .disabled(controller.apps.isEmpty && controller.inputs.isEmpty)
            }
        }
        .alert("Rename", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } }), presenting: renaming) { r in
            TextField("Name", text: $renameText)
            Button("Save") { rename(r.key, to: renameText) }
            if controller.config["names"]?.stringMap[r.key] != nil {
                Button("Use the original name", role: .destructive) { rename(r.key, to: "") }
            }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Choose a name for this on the TV.")
        }
    }

    // MARK: data

    private var visibleApps: [AppEntry] { AppLayout.apps(controller.apps, config: controller.config).filter { !$0.hidden } }
    private var hiddenApps: [AppEntry] { AppLayout.apps(controller.apps, config: controller.config).filter(\.hidden) }
    private var allInputs: [InputEntry] { AppLayout.inputs(controller.inputs, config: controller.config) }

    // MARK: browsing

    private var grid: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                if !visibleApps.isEmpty {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 14)], spacing: 14) {
                        ForEach(visibleApps) { app in
                            Button { controller.launch(app) } label: { AppTile(controller: controller, app: app) }
                                .buttonStyle(.plain)
                        }
                    }
                }
                let inputs = allInputs.filter { !$0.hidden }
                if !inputs.isEmpty {
                    Text("Inputs").font(.title3.weight(.semibold))
                    VStack(spacing: 8) {
                        ForEach(inputs) { input in
                            Button { controller.switchInput(input) } label: { InputRow(controller: controller, input: input) }
                                .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(16)
        }
    }

    // MARK: editing

    private var editor: some View {
        List {
            appSection("Favorites", apps: visibleApps.filter(\.pinned), pinned: true)
            appSection("Apps", apps: visibleApps.filter { !$0.pinned }, pinned: false)
            if !hiddenApps.isEmpty {
                Section("Hidden") {
                    ForEach(hiddenApps) { app in editRow(app) }
                }
            }
            Section("Inputs") {
                ForEach(allInputs) { input in
                    HStack {
                        InputRow(controller: controller, input: input, compact: true)
                        Button("Rename", systemImage: "pencil") { beginRename(input.key, input.label) }
                            .labelStyle(.iconOnly).buttonStyle(.borderless)
                        Button(input.hidden ? "Show" : "Hide", systemImage: input.hidden ? "eye.slash" : "eye") { toggleInput(input) }
                            .labelStyle(.iconOnly).buttonStyle(.borderless)
                            .foregroundStyle(input.hidden ? .secondary : Theme.accent)
                    }
                }
            }
        }
        .environment(\.editMode, .constant(.active))
    }

    private func appSection(_ title: LocalizedStringKey, apps: [AppEntry], pinned: Bool) -> some View {
        Section(title) {
            ForEach(apps) { app in editRow(app) }
                .onMove { from, to in move(apps: apps, pinned: pinned, from: from, to: to) }
            if apps.isEmpty {
                Text(pinned ? "Pin an app to keep it first." : "Nothing here.").foregroundStyle(.secondary).font(.footnote)
            }
        }
    }

    private func editRow(_ app: AppEntry) -> some View {
        HStack(spacing: 12) {
            AppIconView(controller: controller, key: app.packageName, label: app.label, size: 40)
            Text(app.label).lineLimit(1)
            Spacer()
            Button("Rename", systemImage: "pencil") { beginRename(app.packageName, app.label) }
                .labelStyle(.iconOnly).buttonStyle(.borderless)
            Button(app.pinned ? "Unpin" : "Pin", systemImage: app.pinned ? "pin.fill" : "pin") { togglePin(app) }
                .labelStyle(.iconOnly).buttonStyle(.borderless)
                .foregroundStyle(app.pinned ? Theme.accent : .secondary)
            Button(app.hidden ? "Show" : "Hide", systemImage: app.hidden ? "eye.slash" : "eye") { toggleHidden(app) }
                .labelStyle(.iconOnly).buttonStyle(.borderless)
                .foregroundStyle(app.hidden ? .secondary : Theme.accent)
        }
    }

    // MARK: edits (all through config.set)

    private func togglePin(_ app: AppEntry) {
        Haptics.select()
        var pinned = controller.config["pinnedApps"]?.stringArray ?? []
        if let i = pinned.firstIndex(of: app.packageName) { pinned.remove(at: i) } else { pinned.append(app.packageName) }
        controller.set("pinnedApps", .array(pinned.map(JSONValue.string)))
    }

    private func toggleHidden(_ app: AppEntry) {
        Haptics.select()
        var hidden = Set(controller.config["hiddenApps"]?.stringArray ?? [])
        if hidden.contains(app.packageName) { hidden.remove(app.packageName) } else { hidden.insert(app.packageName) }
        controller.set("hiddenApps", .array(hidden.sorted().map(JSONValue.string)))
    }

    private func toggleInput(_ input: InputEntry) {
        Haptics.select()
        var hidden = Set(controller.config["hiddenInputs"]?.stringArray ?? [])
        if hidden.contains(input.key) { hidden.remove(input.key) } else { hidden.insert(input.key) }
        controller.set("hiddenInputs", .array(hidden.sorted().map(JSONValue.string)))
    }

    /// Reordering stays inside favorites or inside the other apps, like the TV's own Move.
    private func move(apps group: [AppEntry], pinned: Bool, from: IndexSet, to: Int) {
        var keys = group.map(\.packageName)
        keys.move(fromOffsets: from, toOffset: to)
        Haptics.select()
        if pinned {
            let rest = (controller.config["pinnedApps"]?.stringArray ?? []).filter { !keys.contains($0) }
            controller.set("pinnedApps", .array((keys + rest).map(JSONValue.string)))
        } else {
            let rest = (controller.config["manualAppOrder"]?.stringArray ?? []).filter { !keys.contains($0) }
            controller.set(["manualAppOrder": .array((keys + rest).map(JSONValue.string)), "appSort": .string("manual")])
        }
    }

    private func beginRename(_ key: String, _ current: String) {
        renameText = current
        renaming = Renaming(key: key, current: current)
    }

    private func rename(_ key: String, to name: String) {
        var names = controller.config["names"]?.stringMap ?? [:]
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { names.removeValue(forKey: key) } else { names[key] = trimmed }
        controller.set("names", .object(names.mapValues(JSONValue.string)))
    }
}

/// Applies the live config to the TV's last `apps`/`inputs` lists so edits show before the TV re-sends them.
enum AppLayout {
    static func apps(_ apps: [AppEntry], config: [String: JSONValue]) -> [AppEntry] {
        let pinned = config["pinnedApps"]?.stringArray ?? apps.filter(\.pinned).map(\.packageName)
        let hidden = Set(config["hiddenApps"]?.stringArray ?? apps.filter(\.hidden).map(\.packageName))
        let names = config["names"]?.stringMap ?? [:]
        let manual = config["manualAppOrder"]?.stringArray ?? []
        let sort = config["appSort"]?.stringValue ?? "usage"
        let base = apps.enumerated().map { index, app -> (Int, AppEntry) in
            var a = app
            a.pinned = pinned.contains(app.packageName)
            a.hidden = hidden.contains(app.packageName)
            if let n = names[app.packageName] { a.label = n }
            return (index, a)
        }
        func rank(_ list: [String], _ key: String) -> Int { list.firstIndex(of: key) ?? Int.max }
        let sorted = base.sorted { l, r in
            let (lp, rp) = (rank(pinned, l.1.packageName), rank(pinned, r.1.packageName))
            if lp != rp { return lp < rp }
            if sort == "manual" {
                let (lm, rm) = (rank(manual, l.1.packageName), rank(manual, r.1.packageName))
                if lm != rm { return lm < rm }
            }
            if sort == "alphabetical" { return l.1.label.localizedCaseInsensitiveCompare(r.1.label) == .orderedAscending }
            return l.0 < r.0
        }
        return sorted.map(\.1)
    }

    static func inputs(_ inputs: [InputEntry], config: [String: JSONValue]) -> [InputEntry] {
        let hidden = Set(config["hiddenInputs"]?.stringArray ?? inputs.filter(\.hidden).map(\.key))
        let names = config["names"]?.stringMap ?? [:]
        let icons = config["inputIcons"]?.stringMap ?? [:]
        return inputs.map { input in
            var i = input
            i.hidden = hidden.contains(input.key)
            if let n = names[input.key] { i.label = n }
            if let icon = icons[input.key] { i.icon = icon }
            return i
        }
    }
}

// MARK: - Pieces

struct AppIconView: View {
    let controller: TVController
    let key: String
    let label: String
    let size: CGFloat

    var body: some View {
        Group {
            if let data = controller.icons[key], let image = IconCache.image(key: key, data: data) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    LinearGradient(colors: [Theme.accent.opacity(0.35), Theme.accent.opacity(0.12)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    Text(String(label.prefix(1)).uppercased()).font(.system(size: size * 0.45, weight: .bold, design: .rounded)).foregroundStyle(.white.opacity(0.9))
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
        .task { controller.requestIcon(key) }
    }
}

struct AppTile: View {
    let controller: TVController
    let app: AppEntry

    var body: some View {
        VStack(spacing: 0) {
            Group {
                if let data = controller.icons[app.packageName], let image = IconCache.image(key: app.packageName, data: data), app.banner {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    ZStack {
                        LinearGradient(colors: [Theme.accent.opacity(0.28), Theme.accent.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing)
                        AppIconView(controller: controller, key: app.packageName, label: app.label, size: 54)
                    }
                }
            }
            .aspectRatio(16.0 / 9.0, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .clipped()
            HStack(spacing: 6) {
                if app.pinned { Image(systemName: "pin.fill").font(.caption2).foregroundStyle(Theme.accent) }
                Text(app.label).font(.subheadline.weight(.medium)).lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
        }
        .background(Theme.card)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .task { if app.banner { controller.requestIcon(app.packageName) } }
    }
}

struct InputRow: View {
    let controller: TVController
    let input: InputEntry
    var compact = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).font(.title3).foregroundStyle(Theme.accent).frame(width: 32)
            Text(input.label).foregroundStyle(input.hidden ? .secondary : .primary)
            Spacer(minLength: 0)
            if !compact { Image(systemName: "arrow.right.circle").foregroundStyle(.tertiary) }
        }
        .padding(.horizontal, compact ? 0 : 14)
        .padding(.vertical, compact ? 2 : 14)
        .background(compact ? Color.clear : Theme.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .contentShape(Rectangle())
    }

    private var symbol: String {
        switch input.icon?.lowercased() {
        case "game", "console", "gamecontroller": "gamecontroller.fill"
        case "tv", "cable", "tuner", "antenna": "tv"
        case "stick", "streaming", "box": "tv.and.mediabox"
        case "pc", "computer": "desktopcomputer"
        default: "rectangle.connected.to.line.below"
        }
    }
}
