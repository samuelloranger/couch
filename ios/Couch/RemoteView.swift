import CouchKit
import SwiftUI
import UIKit

struct RemoteView: View {
    let controller: TVController
    @State private var typed = ""
    @State private var fullScreen = false
    @FocusState private var keyboardFocused: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                LiveCard(controller: controller, fullScreen: $fullScreen)
                AdbNotice(status: controller.state?.adb ?? .unknown)
                if let playing = controller.state?.nowPlaying { NowPlayingStrip(playing: playing) }
                TouchPad(controller: controller)
                RemoteButtons(controller: controller)
                keyboard
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
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
        .frame(minHeight: 52)
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
        case .ready, .unknown:
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
                VideoSurface(pipeline: controller.video)
            }
            overlay
        }
        .aspectRatio(16.0 / 9.0, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
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
            VStack(spacing: 8) {
                Image(systemName: "play.fill").font(.system(size: 34))
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
            VideoSurface(pipeline: controller.video).ignoresSafeArea()
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

// MARK: - Touch pad

struct TouchPad: View {
    let controller: TVController
    @State private var origin: CGPoint?
    @State private var moved = false
    private let step: CGFloat = 38

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Theme.card)
            VStack {
                Image(systemName: "chevron.compact.up")
                Spacer()
                Image(systemName: "chevron.compact.down")
            }
            .padding(.vertical, 10)
            HStack {
                Image(systemName: "chevron.compact.left")
                Spacer()
                Image(systemName: "chevron.compact.right")
            }
            .padding(.horizontal, 10)
            VStack(spacing: 6) {
                Image(systemName: "hand.tap").font(.title2)
                Text("Swipe to move, tap to select").font(.footnote)
            }
            .foregroundStyle(.secondary)
        }
        .font(.title2)
        .foregroundStyle(.tertiary)
        .frame(height: 170)
        .glassEffect(.regular, in: .rect(cornerRadius: 28))
        .contentShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    let start = origin ?? value.startLocation
                    if origin == nil { origin = start }
                    let dx = value.location.x - start.x, dy = value.location.y - start.y
                    guard max(abs(dx), abs(dy)) >= step else { return }
                    let key: RemoteKey = abs(dx) > abs(dy) ? (dx > 0 ? .right : .left) : (dy > 0 ? .down : .up)
                    controller.press(key)
                    moved = true
                    origin = value.location
                }
                .onEnded { _ in
                    if !moved { controller.press(.ok); Haptics.press() }
                    origin = nil
                    moved = false
                }
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Touch pad"))
        .accessibilityHint(Text("Swipe to move, double tap to select"))
        .accessibilityAdjustableAction { direction in
            controller.press(direction == .increment ? .down : .up)
        }
    }
}

// MARK: - Buttons

struct RemoteButtons: View {
    let controller: TVController

    var body: some View {
        VStack(spacing: 12) {
            row([.sym("arrow.uturn.backward", .back, "Back"), .sym("house.fill", .home, "Home"), .sym("line.3.horizontal", .menu, "Menu")])
            row([.sym("backward.fill", .rewind, "Rewind"), .sym("playpause.fill", .playPause, "Play or pause"), .sym("forward.fill", .fastForward, "Fast forward")])
            row([.sym("speaker.minus.fill", .volumeDown, "Volume down"), .sym("speaker.slash.fill", .mute, "Mute"), .sym("speaker.plus.fill", .volumeUp, "Volume up")])
            row([.sym("power", .power, "Power"), .sym("rectangle.connected.to.line.below", .input, "Input"), .sym("gearshape.fill", .settings, "Settings")])
        }
    }

    struct Item: Identifiable {
        let symbol: String
        let key: RemoteKey
        let label: LocalizedStringKey
        var id: String { key.rawValue }
        static func sym(_ s: String, _ k: RemoteKey, _ l: LocalizedStringKey) -> Item { Item(symbol: s, key: k, label: l) }
    }

    private func row(_ items: [Item]) -> some View {
        HStack(spacing: 12) {
            ForEach(items) { item in
                Button { controller.press(item.key) } label: {
                    Image(systemName: item.symbol)
                        .font(.title2)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .foregroundStyle(item.key == .power ? Color.red : Color.primary)
                }
                .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 20))
                .accessibilityLabel(Text(item.label))
            }
        }
    }
}
