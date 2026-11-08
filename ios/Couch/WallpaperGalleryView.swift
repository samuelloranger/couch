import CouchKit
import ImageIO
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct WallpaperGalleryView: View {
    let controller: TVController
    @State private var selection: [PhotosPickerItem] = []
    @State private var selected: WallpaperEntry?
    @State private var task: Task<Void, Never>?
    @State private var uploading = false
    @State private var batchIndex = 0
    @State private var batchCount = 0
    private var gallery: WallpaperGallery { controller.gallery }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if !gallery.supported {
                    ContentUnavailableView("Update Couch on the TV", systemImage: "photo.on.rectangle.angled",
                        description: Text("This TV needs a newer version of Couch to manage ambient images."))
                } else {
                    Text("Images alternate every two minutes during ambient mode.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Text("JPEG, PNG or WebP. Up to 200 images; 20 MB per image.")
                        .font(.footnote).foregroundStyle(.secondary)
                    if controller.config["wallpaper"] != .string("custom") {
                        Button("Use my images for ambient mode") { controller.set("wallpaper", .string("custom")) }
                            .disabled(!gallery.connected)
                    }
                    if uploading {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Uploading image \(batchIndex) of \(batchCount)").font(.subheadline)
                            ProgressView(value: gallery.progress ?? 0)
                            Button("Cancel upload", role: .cancel) { task?.cancel() }
                        }
                    }
                    if let error = gallery.error {
                        VStack(alignment: .leading, spacing: 8) {
                            Label(error, systemImage: "exclamationmark.circle").font(.subheadline)
                            Button("Try again") { gallery.refresh() }.disabled(!gallery.connected)
                        }
                        .foregroundStyle(.secondary)
                    }
                    if !gallery.connected {
                        ContentUnavailableView("Not connected", systemImage: "wifi.slash",
                            description: Text("Connect to the TV to see and upload images."))
                    } else if gallery.entries.isEmpty && !gallery.loading {
                        ContentUnavailableView("No ambient images", systemImage: "photo.on.rectangle",
                            description: Text("Add photos from your iPhone to start a slideshow on the TV."))
                    } else {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 145))], alignment: .leading, spacing: 20) {
                            ForEach(gallery.entries) { image in
                                Button { selected = image; gallery.request(image.id, preview: true) } label: {
                                    VStack(alignment: .leading, spacing: 8) {
                                        thumbnail(image)
                                            .aspectRatio(16 / 9, contentMode: .fit)
                                            .clipShape(.rect(cornerRadius: 10))
                                        Text(image.name).font(.caption).lineLimit(2).foregroundStyle(.primary)
                                    }
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(Text("Preview \(image.name)"))
                                .task { gallery.request(image.id) }
                            }
                        }
                    }
                    if gallery.loading { ProgressView().frame(maxWidth: .infinity) }
                }
            }.padding()
        }
        .navigationTitle("Ambient images")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                PhotosPicker(selection: $selection, maxSelectionCount: 20, matching: .images) {
                    Label("Add photos", systemImage: "photo.badge.plus")
                }.disabled(!gallery.connected || !gallery.supported || uploading || controller.isDemo)
            }
        }
        .refreshable { gallery.refresh() }
        .onAppear { gallery.active = true; gallery.refresh() }
        .onDisappear { gallery.active = false; task?.cancel() }
        .onChange(of: selection) { _, items in upload(items) }
        .onChange(of: gallery.entries) { _, images in
            if let selected, !images.contains(where: { $0.id == selected.id }) { self.selected = nil }
        }
        .sheet(item: $selected) { image in WallpaperPreview(gallery: gallery, image: image) }
    }

    @ViewBuilder private func thumbnail(_ image: WallpaperEntry) -> some View {
        if let data = gallery.thumbnails[image.id], let ui = UIImage(data: data) {
            Image(uiImage: ui).resizable().scaledToFill().frame(maxWidth: .infinity).clipped()
        } else {
            Rectangle().fill(.quaternary).overlay { Image(systemName: "photo").foregroundStyle(.secondary) }
        }
    }

    private func upload(_ items: [PhotosPickerItem]) {
        guard !items.isEmpty, !uploading else { return }
        uploading = true; batchCount = items.count; batchIndex = 1
        task = Task {
            defer { uploading = false; selection = []; task = nil }
            do {
                for (index, item) in items.enumerated() {
                    try Task.checkCancellation()
                    batchIndex = index + 1
                    guard let raw = try await item.loadTransferable(type: Data.self) else {
                        throw GalleryError.failed(String(localized: "This photo could not be opened."))
                    }
                    let data = try await Task.detached(priority: .userInitiated) { try prepareWallpaper(raw) }.value
                    try Task.checkCancellation()
                    try await gallery.upload(data, name: "Photo-\(Date().timeIntervalSince1970).jpg")
                }
                Haptics.success()
            } catch is CancellationError {
                gallery.progress = nil
            } catch { gallery.fail(error.localizedDescription); Haptics.failure() }
        }
    }
}

/// ImageIO applies orientation while downsampling, without decoding a giant original bitmap.
nonisolated func prepareWallpaper(_ data: Data, maxPixelSize: Int = 3840) throws -> Data {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
          let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            kCGImageSourceShouldCacheImmediately: true,
          ] as CFDictionary) else { throw GalleryError.failed(String(localized: "This photo could not be opened.")) }
    let result = NSMutableData()
    guard let dest = CGImageDestinationCreateWithData(result, UTType.jpeg.identifier as CFString, 1, nil) else {
        throw GalleryError.failed(String(localized: "This photo could not be prepared."))
    }
    CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: 0.95] as CFDictionary)
    guard CGImageDestinationFinalize(dest), result.length <= WallpaperWire.maxBytes else {
        throw GalleryError.failed(String(localized: "Choose an image smaller than 20 MB."))
    }
    return result as Data
}

private struct WallpaperPreview: View {
    let gallery: WallpaperGallery
    let image: WallpaperEntry
    @Environment(\.dismiss) private var dismiss
    @State private var confirmDelete = false
    var body: some View {
        NavigationStack {
            VStack {
                if let bytes = gallery.previews[image.id], let ui = UIImage(data: bytes) {
                    Image(uiImage: ui).resizable().scaledToFit().accessibilityLabel(image.name)
                } else if let error = gallery.error {
                    ContentUnavailableView(error, systemImage: "photo")
                } else { ProgressView() }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle(image.name).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Delete image", systemImage: "trash", role: .destructive) { confirmDelete = true }
                        .disabled(!gallery.connected)
                }
            }
            .confirmationDialog("Delete this image from the TV?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete image", role: .destructive) { gallery.delete(image.id); dismiss() }
                Button("Cancel", role: .cancel) {}
            }
        }
    }
}
