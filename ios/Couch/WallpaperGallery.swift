import CouchKit
import Foundation
import Observation

/// Gallery state and one acknowledged, cancellable upload over the existing paired connection.
@MainActor @Observable
final class WallpaperGallery {
    var entries: [WallpaperEntry] = []
    var thumbnails: [String: Data] = [:]
    var previews: [String: Data] = [:]
    var loading = false
    var error: String?
    var progress: Double?
    var connected = false
    var supported = false
    var active = false
    var requestGeneration = 0
    @ObservationIgnored var send: (OutgoingMessage) -> Void = { _ in }
    @ObservationIgnored private var requested: Set<String> = []
    @ObservationIgnored private var waiter: CheckedContinuation<Void, Error>?
    @ObservationIgnored private var timeout: Task<Void, Never>?
    @ObservationIgnored private var transfer: String?
    @ObservationIgnored private var expectedOffset = 0
    @ObservationIgnored private var expectedDone = false

    func connection(_ value: Bool) {
        connected = value
        if !value {
            requested.removeAll(); loading = false
            fail(String(localized: "The TV disconnected. Try again when connected."))
        } else if active && supported { refresh() }
    }

    func refresh() {
        guard connected, supported else { return }
        loading = true; error = nil
        requestGeneration += 1
        send(.wallpaper(action: "list", fields: [:]))
    }

    func receive(_ message: IncomingMessage) {
        switch message {
        case .wallpapers(let images):
            entries = images; loading = false
            let ids = Set(images.map(\.id))
            thumbnails = thumbnails.filter { ids.contains($0.key) }
            previews = previews.filter { ids.contains($0.key) }
        case .wallpapersChanged: if active { refresh() }
        case .wallpaperImage(let id, let preview, let jpeg):
            requested.remove((preview ? "p:" : "t:") + id)
            if let jpeg {
                if preview { previews = [id: jpeg] } else { thumbnails[id] = jpeg }
            } else { error = String(localized: "This image could not be opened.") }
        case .wallpaperUpload(let ack):
            guard ack.id == transfer, ack.offset == expectedOffset, ack.done == expectedDone else { return }
            finishWaiter(nil)
        default: break
        }
    }

    func request(_ id: String, preview: Bool = false) {
        guard connected, supported else { return }
        if preview ? previews[id] != nil : thumbnails[id] != nil { return }
        let key = (preview ? "p:" : "t:") + id
        guard requested.insert(key).inserted else { return }
        send(.wallpaper(action: "image", fields: ["id": .string(id), "preview": .bool(preview)]))
    }

    func delete(_ id: String) {
        guard connected, supported else { return }
        send(.wallpaper(action: "delete", fields: ["id": .string(id)]))
    }

    func fail(_ text: String) {
        error = text; loading = false; requested.removeAll()
        finishWaiter(GalleryError.failed(text))
    }

    func upload(_ data: Data, name: String) async throws {
        guard connected, supported, transfer == nil, !data.isEmpty, data.count <= WallpaperWire.maxBytes else {
            throw GalleryError.failed(String(localized: "Connect to an updated TV and choose an image smaller than 20 MB."))
        }
        let id = UUID().uuidString
        transfer = id; progress = 0; error = nil
        defer { transfer = nil; progress = nil; finishWaiter(CancellationError()) }
        do {
            try await step("upload.begin", ["id": .string(id), "name": .string(name), "bytes": .int(data.count)], offset: 0)
            var offset = 0
            while let chunk = WallpaperWire.chunk(data, offset: offset) {
                try Task.checkCancellation()
                let end = offset + chunk.count
                try await step("upload.chunk", ["id": .string(id), "offset": .int(offset), "data": .string(chunk.base64EncodedString())], offset: end)
                offset = end; progress = Double(offset) / Double(data.count)
            }
            try await step("upload.finish", ["id": .string(id), "bytes": .int(data.count)], offset: data.count, done: true)
            refresh()
        } catch {
            if connected { send(.wallpaper(action: "upload.cancel", fields: ["id": .string(id)])) }
            throw error
        }
    }

    private func step(_ action: String, _ fields: [String: JSONValue], offset: Int, done: Bool = false) async throws {
        try Task.checkCancellation()
        guard connected else { throw GalleryError.failed(String(localized: "Not connected")) }
        expectedOffset = offset; expectedDone = done
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                waiter = c
                timeout = Task { [weak self] in
                    do { try await Task.sleep(for: .seconds(20)) } catch { return }
                    self?.finishWaiter(GalleryError.failed(String(localized: "The upload timed out. Try again.")))
                }
                send(.wallpaper(action: action, fields: fields))
            }
        } onCancel: { Task { @MainActor [weak self] in self?.finishWaiter(CancellationError()) } }
    }

    private func finishWaiter(_ error: Error?) {
        timeout?.cancel(); timeout = nil
        let pending = waiter; waiter = nil
        if let error { pending?.resume(throwing: error) } else { pending?.resume() }
    }
}

enum GalleryError: LocalizedError {
    case failed(String)
    var errorDescription: String? { if case .failed(let text) = self { return text }; return nil }
}
