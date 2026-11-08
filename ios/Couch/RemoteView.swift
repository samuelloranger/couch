import CouchKit
import SwiftUI
import UIKit

struct RemoteView: View {
    @Environment(AppModel.self) private var model
    let controller: TVController
    @State private var typed = ""
    @State private var fullScreen = false
    @FocusState private var keyboardFocused: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                LiveCard(controller: controller, fullScreen: $fullScreen)
                AdbNotice(status: controller.state?.adb ?? .unknown)
                if let playing = controller.state?.nowPlaying { NowPlayingStrip(playing: playing) }
                if controller.link != .connected {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Connecting…").font(.footnote.weight(.medium))
                        Text("Presses are sent as soon as the TV answers.").font(.footnote).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.8)
                    }
                    .transition(.opacity)
                }
                DPad(controller: controller)
                    .opacity(controller.link == .connected ? 1 : 0.6)
                RemoteButtons(controller: controller)
                keyboard
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
        .contentMargins(.bottom, 8, for: .scrollContent)
        .scrollDismissesKeyboard(.interactively)
        .task { if model.demoMode && model.demoScreen == "full" { fullScreen = true } }
        .fullScreenCover(isPresented: $fullScreen) { FullScreenLive(controller: controller) }
    }

    private var keyboard: some View {
        HStack(spacing: 10) {
            Image(systemName: "keyboard").foregroundStyle(.secondary)
            TextField("Type on the TV", text: $typed)
                .focused($keyboardFocused)
                .submitLabel(.send)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit(sendTyped)
            Button("Send", systemImage: "arrow.up.circle.fill", action: sendTyped)
                .labelStyle(.iconOnly)
                .font(.title2)
                .disabled(typed.isEmpty)
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 48)
        .glassEffect(.regular, in: .rect(cornerRadius: 16))
    }

    private func sendTyped() {
        guard !typed.isEmpty else { return }
        controller.type(typed)
        Haptics.tap()
        typed = ""
        keyboardFocused = true
    }
}

// MARK: - Notices

struct AdbNotice: View {
    let status: AdbStatus

    var body: some View {
        switch status {
        case .dialog:
            notice("hand.tap.fill", "Allow debugging on the TV", "Pick Allow on the dialog the TV is showing. Keys, typing and live view need it.")
        case .off:
            notice("exclamationmark.triangle.fill", "Turn on debugging", "On the TV, open Settings, Developer options, and turn on USB debugging.")
        case .blocked:
            notice("lock.fill", "Debugging isn't available", "This TV doesn't let Couch use debugging, so keys, typing and live view won't work. Apps and settings still do.")
        case .ready, .root, .unknown:
            EmptyView()
        }
    }

    private func notice(_ icon: String, _ title: LocalizedStringKey, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).font(.title3).foregroundStyle(.yellow)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(text).font(.footnote).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .glassEffect(.regular, in: .rect(cornerRadius: 16))
    }
}

struct NowPlayingStrip: View {
    let playing: NowPlaying

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "play.circle.fill").foregroundStyle(Theme.accent)
            VStack(alignment: .leading, spacing: 1) {
                Text(playing.title).font(.subheadline.weight(.medium)).lineLimit(1)
                if let sub = [playing.artist, playing.app].compactMap({ $0 }).first {
                    Text(sub).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 4)
    }
}

// MARK: - Live view

struct LiveCard: View {
    let controller: TVController
    @Binding var fullScreen: Bool

    var body: some View {
        ZStack {
            Color.black
            if controller.wantsStream {
                VideoSurface(pipeline: controller.video, active: !fullScreen)
            }
            overlay
        }
        .frame(maxWidth: .infinity)
        .frame(height: controller.wantsStream ? nil : 92)
        .aspectRatio(controller.wantsStream ? 16.0 / 9.0 : nil, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .animation(.snappy, value: controller.wantsStream)
        .overlay(alignment: .topTrailing) { controls }
        .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .onTapGesture { toggle() }
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(Text(controller.wantsStream ? stopLabel : startLabel))
    }

    private var stopLabel: LocalizedStringKey { "Stop live view" }
    private var startLabel: LocalizedStringKey { "Start live view" }

    private func toggle() {
        Haptics.tap()
        if controller.wantsStream { controller.stopStream() } else { controller.startStream() }
    }

    @ViewBuilder private var overlay: some View {
        if !controller.wantsStream {
            HStack(spacing: 12) {
                Image(systemName: "play.fill").font(.system(size: 26))
                Text("Tap to watch the TV").font(.subheadline)
            }
            .foregroundStyle(.white.opacity(0.85))
        } else if let status = controller.stream, status.state == .needsAdb || status.state == .error || status.state == .ended {
            VStack(spacing: 8) {
                Image(systemName: status.state == .ended ? "stop.circle" : "exclamationmark.triangle.fill").font(.title)
                Text(statusText(status)).font(.footnote).multilineTextAlignment(.center)
            }
            .foregroundStyle(.white.opacity(0.85))
            .padding()
        } else if !controller.hasPicture {
            ProgressView().tint(.white)
        }
    }

    private func statusText(_ s: StreamStatus) -> String {
        if let m = s.message, !m.isEmpty { return m }
        switch s.state {
        case .needsAdb: return String(localized: "Live view needs debugging turned on for the TV.")
        case .ended: return String(localized: "The TV stopped sharing its screen.")
        default: return String(localized: "Live view stopped.")
        }
    }

    @ViewBuilder private var controls: some View {
        if controller.wantsStream {
            HStack(spacing: 8) {
                Menu {
                    Picker("Quality", selection: Binding(get: { controller.quality }, set: { controller.setQuality($0) })) {
                        Text("High, 1080p").tag(StreamQuality.high)
                        Text("Medium, 720p").tag(StreamQuality.medium)
                        Text("Low, 540p").tag(StreamQuality.low)
                    }
                } label: {
                    Image(systemName: "dial.medium").frame(width: 40, height: 40)
                }
                Button("Full screen", systemImage: "arrow.up.left.and.arrow.down.right") { fullScreen = true }
                    .labelStyle(.iconOnly)
                    .frame(width: 40, height: 40)
            }
            .foregroundStyle(.white)
            .glassEffect(.regular, in: .capsule)
            .padding(10)
        }
    }
}

struct FullScreenLive: View {
    let controller: TVController
    @Environment(\.dismiss) private var dismiss
    @State private var showControls = true

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(pipeline: controller.video, active: true).ignoresSafeArea()
            Color.clear.contentShape(Rectangle()).onTapGesture { withAnimation(.snappy) { showControls.toggle() } }
            if showControls {
                VStack {
                    HStack {
                        Button("Close", systemImage: "xmark") { dismiss() }
                            .labelStyle(.iconOnly).font(.title3).frame(width: 48, height: 48)
                            .glassEffect(.regular.interactive(), in: .circle)
                        Spacer()
                    }
                    Spacer()
                    HStack(alignment: .bottom) {
                        FullScreenPad(controller: controller)
                        Spacer()
                        VStack(spacing: 10) {
                            overlayKey("arrow.uturn.backward", .back)
                            overlayKey("house.fill", .home)
                            overlayKey("playpause.fill", .playPause)
                        }
                    }
                }
                .padding(20)
                .transition(.opacity)
            }
        }
        .statusBarHidden()
        .onAppear { Orientation.lock(.landscape) }
        .onDisappear { Orientation.lock(.portrait) }
    }

    private func overlayKey(_ symbol: String, _ key: RemoteKey) -> some View {
        Button { controller.press(key) } label: {
            Image(systemName: symbol).font(.title3).frame(width: 54, height: 54)
        }
        .glassEffect(.regular.interactive(), in: .circle)
        .foregroundStyle(.white)
    }
}

struct FullScreenPad: View {
    let controller: TVController

    var body: some View {
        Grid(horizontalSpacing: 6, verticalSpacing: 6) {
            GridRow {
                Color.clear.frame(width: 54, height: 54)
                key("chevron.up", .up)
                Color.clear.frame(width: 54, height: 54)
            }
            GridRow {
                key("chevron.left", .left)
                key("circle.fill", .ok)
                key("chevron.right", .right)
            }
            GridRow {
                Color.clear.frame(width: 54, height: 54)
                key("chevron.down", .down)
                Color.clear.frame(width: 54, height: 54)
            }
        }
    }

    private func key(_ symbol: String, _ key: RemoteKey) -> some View {
        Button { controller.press(key) } label: {
            Image(systemName: symbol).font(.title3).frame(width: 54, height: 54)
        }
        .glassEffect(.regular.interactive(), in: .circle)
        .foregroundStyle(.white)
    }
}

@MainActor
enum Orientation {
    static func lock(_ mask: UIInterfaceOrientationMask) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }
}

// MARK: - Press and hold

/// Fires on touch-down, so it feels instant. While `repeats` is on, holding past 400 ms sends a `tap`
/// every 120 ms until release (taps, not key down/up: the TV only truly holds keys through its relay).
struct KeyPress<Content: View>: View {
    let controller: TVController
    let key: RemoteKey
    var repeats = false
    @ViewBuilder let content: (Bool) -> Content
    @GestureState private var down = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var repeater: Task<Void, Never>?

    var body: some View {
        content(down)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .updating($down) { _, state, _ in state = true }
            )
            .onChange(of: down) { _, isDown in
                if isDown { begin() } else { stop() }
            }
            .onDisappear(perform: stop)
            .onChange(of: scenePhase) { _, phase in if phase != .active { stop() } }
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { controller.press(key) }
    }

    private func begin() {
        controller.press(key)
        stop()
        guard repeats else { return }
        repeater = Task {
            try? await Task.sleep(for: .milliseconds(400))
            while !Task.isCancelled && controller.link == .connected {
                controller.press(key)
                try? await Task.sleep(for: .milliseconds(120))
            }
        }
    }

    private func stop() {
        repeater?.cancel()
        repeater = nil
    }
}

// MARK: - D-pad

struct DPad: View {
    let controller: TVController
    private let outer: CGFloat = 204
    private let arm: CGFloat = 64
    private let center: CGFloat = 84

    var body: some View {
        ZStack {
            Circle().fill(Theme.card).glassEffect(.regular, in: .circle)
            arrow("chevron.up", .up, label: "Up").offset(y: -70)
            arrow("chevron.down", .down, label: "Down").offset(y: 70)
            arrow("chevron.left", .left, label: "Left").offset(x: -70)
            arrow("chevron.right", .right, label: "Right").offset(x: 70)
            KeyPress(controller: controller, key: .ok) { pressed in
                Circle()
                    .fill(Theme.accent.opacity(pressed ? 0.95 : 0.8))
                    .overlay(Text("OK").font(.title3.weight(.bold)).foregroundStyle(.black.opacity(0.75)))
                    .frame(width: center, height: center)
                    .scaleEffect(pressed ? 0.94 : 1)
                    .animation(.snappy(duration: 0.12), value: pressed)
                    .contentShape(Circle())
            }
            .accessibilityLabel(Text("OK"))
        }
        .frame(width: outer, height: outer)
        .frame(maxWidth: .infinity)
    }

    private func arrow(_ symbol: String, _ key: RemoteKey, label: LocalizedStringKey) -> some View {
        KeyPress(controller: controller, key: key, repeats: true) { pressed in
            Image(systemName: symbol)
                .font(.title2.weight(.semibold))
                .foregroundStyle(pressed ? Theme.accent : Color.primary)
                .frame(width: arm, height: arm)
                .background(pressed ? Color.white.opacity(0.12) : .clear, in: Circle())
                .contentShape(Circle())
        }
        .accessibilityLabel(Text(label))
    }
}

// MARK: - Buttons

struct RemoteButtons: View {
    let controller: TVController

    var body: some View {
        VStack(spacing: 10) {
            row([.sym("arrow.uturn.backward", .back, "Back"), .sym("house.fill", .home, "Home"), .sym("line.3.horizontal", .menu, "Menu")])
            row([.sym("backward.fill", .rewind, "Rewind"), .sym("playpause.fill", .playPause, "Play or pause"), .sym("forward.fill", .fastForward, "Fast forward")])
            row([.sym("speaker.minus.fill", .volumeDown, "Volume down", repeats: true), .sym("speaker.slash.fill", .mute, "Mute"), .sym("speaker.plus.fill", .volumeUp, "Volume up", repeats: true)])
            row([.sym("power", .power, "Power"), .sym("rectangle.connected.to.line.below", .input, "Input"), .sym("gearshape.fill", .settings, "Settings")])
        }
    }

    struct Item: Identifiable {
        let symbol: String
        let key: RemoteKey
        let label: LocalizedStringKey
        var repeats = false
        var id: String { key.rawValue }
        static func sym(_ s: String, _ k: RemoteKey, _ l: LocalizedStringKey, repeats: Bool = false) -> Item {
            Item(symbol: s, key: k, label: l, repeats: repeats)
        }
    }

    private func row(_ items: [Item]) -> some View {
        HStack(spacing: 10) {
            ForEach(items) { item in
                KeyPress(controller: controller, key: item.key, repeats: item.repeats) { pressed in
                    Image(systemName: item.symbol)
                        .font(.title3)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .foregroundStyle(item.key == .power ? Color.red : Color.primary)
                        .glassEffect(.regular, in: .rect(cornerRadius: 18))
                        .opacity(pressed ? 0.6 : 1)
                        .contentShape(RoundedRectangle(cornerRadius: 18))
                }
                .accessibilityLabel(Text(item.label))
            }
        }
    }
}
