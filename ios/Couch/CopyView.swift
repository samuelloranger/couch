import CouchKit
import SwiftUI

struct CopyView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let controller: TVController

    @State private var groups: Set<CopyGroup> = [.look, .ambient]
    @State private var working: String?
    @State private var result: LocalizedStringKey?
    @State private var namingBackup = false
    @State private var backupName = ""

    private var others: [TVController] { model.controllers.filter { $0.id != controller.id } }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(CopyGroup.allCases) { g in
                        Toggle(label(g), isOn: Binding(get: { groups.contains(g) }, set: { on in
                            if on { groups.insert(g) } else { groups.remove(g) }
                        }))
                    }
                } header: {
                    Text("What to copy")
                } footer: {
                    Text("Channels, inputs, remote buttons and the hidden menus stay on each TV.")
                }

                Section("Copy to another TV") {
                    if others.isEmpty {
                        Text("Pair a second TV to copy settings to it.").foregroundStyle(.secondary)
                    }
                    ForEach(others) { target in
                        Button {
                            copy(to: target)
                        } label: {
                            HStack {
                                Label("Copy to \(target.displayName)", systemImage: "arrow.right.doc.on.clipboard")
                                Spacer()
                                if working == target.id { ProgressView() }
                            }
                        }
                        .disabled(groups.isEmpty || working != nil)
                    }
                }

                Section {
                    Button("Save a backup of these", systemImage: "tray.and.arrow.down") {
                        backupName = ""
                        namingBackup = true
                    }
                    .disabled(groups.isEmpty)
                    ForEach(model.backups) { backup in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(backup.name)
                                Text(backup.date.formatted(date: .abbreviated, time: .omitted)).font(.footnote).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Restore") { restore(backup) }
                                .buttonStyle(.bordered)
                                .disabled(backup.patch.isEmpty)
                        }
                        .swipeActions {
                            Button("Delete", role: .destructive) { model.deleteBackup(backup) }
                        }
                    }
                } header: {
                    Text("Backups on this phone")
                } footer: {
                    Text("Restore puts a backup on \(controller.displayName).")
                }

                if let result {
                    Section { Text(result).foregroundStyle(Theme.accent) }
                }
            }
            .navigationTitle("Copy settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .alert("Name this backup", isPresented: $namingBackup) {
                TextField("Name", text: $backupName)
                Button("Save") {
                    let name = backupName.trimmingCharacters(in: .whitespacesAndNewlines)
                    model.saveBackup(name: name.isEmpty ? String(localized: "Backup") : name, from: controller, groups: groups)
                    Haptics.success()
                }
                Button("Cancel", role: .cancel) {}
            }
        }
    }

    private var copiedKey: LocalizedStringKey { "Copied." }
    private var failedKey: LocalizedStringKey { "Couldn't reach that TV." }
    private var restoredKey: LocalizedStringKey { "Restored." }

    private func label(_ g: CopyGroup) -> LocalizedStringKey {
        switch g {
        case .look: "Look"
        case .ambient: "Ambient and wallpaper"
        case .weather: "Weather"
        case .apps: "Apps arrangement"
        case .homeAssistant: "Home Assistant address"
        }
    }

    private func copy(to target: TVController) {
        let patch = CopyPlan.patch(from: controller.config, groups: groups)
        working = target.id
        result = nil
        Task {
            let ok = await model.apply(patch: patch, to: target)
            working = nil
            result = ok ? copiedKey : failedKey
            ok ? Haptics.success() : Haptics.failure()
        }
    }

    private func restore(_ backup: ConfigBackup) {
        controller.set(CopyPlan.sanitized(backup.patch))
        result = restoredKey
        Haptics.success()
    }
}
