import CouchKit
import SwiftUI

struct SettingsView: View {
    let controller: TVController
    @State private var showCopy = false

    var body: some View {
        Group {
            if controller.groups.isEmpty {
                let connected = controller.link == .connected
                let title: LocalizedStringKey = connected ? "No settings yet" : "Not connected"
                let detail: LocalizedStringKey = connected ? "The TV hasn't sent its settings." : "Connect to the TV to see its settings."
                ContentUnavailableView {
                    Label(title, systemImage: "slider.horizontal.3")
                } description: {
                    Text(detail)
                }
            } else {
                Form {
                    aboutSection
                    ForEach(controller.groups) { group in
                        let rows = group.rows.filter { $0.isVisible(in: controller.config) && $0.kind != .unknown }
                        if !rows.isEmpty {
                            Section(group.title) {
                                ForEach(rows) { row in SettingRowView(controller: controller, row: row) }
                            }
                        }
                    }
                }
                .animation(.snappy, value: controller.config)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Copy", systemImage: "square.on.square") { showCopy = true }
                    .disabled(controller.config.isEmpty)
            }
        }
        .sheet(isPresented: $showCopy) { CopyView(controller: controller) }
    }

    @ViewBuilder private var aboutSection: some View {
        if let s = controller.state, s.model != nil || s.version != nil {
            Section("This TV") {
                if let model = s.model { LabeledContent("Model", value: model) }
                if let android = s.android { LabeledContent("Android", value: android) }
                if let version = s.version { LabeledContent("Couch", value: version) }
            }
        }
    }
}

struct SettingRowView: View {
    let controller: TVController
    let row: SettingRow

    private var value: JSONValue? { controller.config[row.key] }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            control
            if let help = row.help, !help.isEmpty {
                Text(help).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder private var control: some View {
        switch row.kind {
        case .toggle:
            Toggle(row.title, isOn: Binding(
                get: { value?.boolValue ?? false },
                set: { Haptics.select(); controller.set(row.key, .bool($0)) }))
        case .choice:
            Picker(row.title, selection: Binding(
                get: { row.options.firstIndex { value?.matches($0.value) ?? false } ?? -1 },
                set: { i in
                    guard row.options.indices.contains(i) else { return }
                    Haptics.select()
                    controller.set(row.key, row.options[i].value)
                })) {
                if !row.options.contains(where: { value?.matches($0.value) ?? false }) { Text("—").tag(-1) }
                ForEach(Array(row.options.enumerated()), id: \.offset) { i, option in Text(option.label).tag(i) }
            }
        case .text:
            TextSettingRow(title: row.title, current: value?.stringValue ?? "") { controller.set(row.key, .string($0)) }
        case .swatches:
            VStack(alignment: .leading, spacing: 10) {
                Text(row.title)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(row.options) { option in
                            let selected = value?.matches(option.value) ?? false
                            Button {
                                Haptics.select()
                                controller.set(row.key, option.value)
                            } label: {
                                Circle()
                                    .fill(Theme.color(hex: option.color) ?? .gray)
                                    .frame(width: 38, height: 38)
                                    .overlay(Circle().strokeBorder(.white, lineWidth: selected ? 3 : 0).padding(-4))
                                    .overlay { if selected { Image(systemName: "checkmark").font(.footnote.bold()).foregroundStyle(.black.opacity(0.7)) } }
                                    .padding(4)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text(option.label))
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                }
            }
        case .unknown:
            EmptyView()
        }
    }
}

struct TextSettingRow: View {
    let title: String
    let current: String
    let commit: (String) -> Void
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        LabeledContent(title) {
            TextField(title, text: $text)
                .multilineTextAlignment(.trailing)
                .focused($focused)
                .submitLabel(.done)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit(save)
        }
        .onAppear { text = current }
        .onChange(of: current) { _, new in if !focused { text = new } }
        .onChange(of: focused) { _, now in if !now { save() } }
    }

    private func save() {
        if text != current { commit(text) }
    }
}
