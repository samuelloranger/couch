import XCTest
import ImageIO
import UniformTypeIdentifiers
@testable import Couch
@testable import CouchKit

final class WallpaperWireTests: XCTestCase {
    func testDecodesGalleryAndPreview() throws {
        let data = Data(#"{"t":"wallpapers","images":[{"id":"one.jpg","name":"One","bytes":42}]}"#.utf8)
        XCTAssertEqual(try IncomingMessage.decode(data), .wallpapers([WallpaperEntry(id: "one.jpg", name: "One", bytes: 42)]))
        XCTAssertEqual(try IncomingMessage.decode(Data(#"{"t":"wallpapers.changed"}"#.utf8)), .wallpapersChanged)
        XCTAssertEqual(try IncomingMessage.decode(Data(#"{"t":"wallpapers.image","id":"one","preview":true,"jpeg":"AQID"}"#.utf8)),
            .wallpaperImage(id: "one", preview: true, jpeg: Data([1, 2, 3])))
    }

    func testChunksReassembleAndStayUnderTheEncryptedFrameLimit() throws {
        let data = Data(repeating: 7, count: WallpaperWire.chunkBytes * 2 + 5)
        var offset = 0
        var result = Data()
        while let chunk = WallpaperWire.chunk(data, offset: offset) {
            XCTAssertLessThanOrEqual(chunk.count, WallpaperWire.chunkBytes)
            let message = OutgoingMessage.wallpaper(action: "upload.chunk", fields: ["data": .string(chunk.base64EncodedString()), "offset": .int(offset)])
            XCTAssertLessThan(try message.encoded().count + 17, FrameCipher.maxFrame)
            XCTAssertFalse(message.isPriority)
            var queue = PendingQueue()
            XCTAssertFalse(queue.append(message))
            result.append(chunk); offset += chunk.count
        }
        XCTAssertEqual(result, data)
        XCTAssertNil(WallpaperWire.chunk(data, offset: -1))
        XCTAssertNil(WallpaperWire.chunk(Data(repeating: 0, count: WallpaperWire.maxBytes + 1), offset: 0))
    }

    func testOldTVsHaveNoGalleryCapability() throws {
        let state = try JSONDecoder().decode(TVState.self, from: Data(#"{"id":"old","name":"Old"}"#.utf8))
        XCTAssertFalse(state.capabilities.contains("wallpapers"))
        let modern = try JSONDecoder().decode(TVState.self, from: Data(#"{"id":"new","name":"New","capabilities":["wallpapers"]}"#.utf8))
        XCTAssertTrue(modern.capabilities.contains("wallpapers"))
    }
}

@MainActor final class WallpaperGalleryTests: XCTestCase {
    func testPhotoPreparationAppliesOrientationAndBoundsTheOutput() throws {
        let context = try XCTUnwrap(CGContext(data: nil, width: 80, height: 40, bitsPerComponent: 8,
            bytesPerRow: 80 * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        let image = try XCTUnwrap(context.makeImage())
        let original = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(original, UTType.jpeg.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, [kCGImagePropertyOrientation: 6] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        let prepared = try prepareWallpaper(original as Data, maxPixelSize: 40)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(prepared as CFData, nil))
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        XCTAssertEqual(properties[kCGImagePropertyPixelWidth] as? Int, 20)
        XCTAssertEqual(properties[kCGImagePropertyPixelHeight] as? Int, 40)
    }

    func testUploadWaitsForEveryAcknowledgementAndCommitsLast() async throws {
        let gallery = WallpaperGallery()
        gallery.connected = true; gallery.supported = true
        var actions: [String] = []
        var received = Data()
        gallery.send = { message in
            guard case .wallpaper(let action, let fields) = message else { return }
            actions.append(action)
            guard let id = fields["id"]?.stringValue else { return }
            if action == "upload.chunk", let encoded = fields["data"]?.stringValue {
                received.append(Data(base64Encoded: encoded)!)
            }
            let done = action == "upload.finish"
            gallery.receive(.wallpaperUpload(WallpaperUploadAck(id: id, offset: received.count, done: done)))
        }
        let data = Data(repeating: 12, count: WallpaperWire.chunkBytes + 9)
        try await gallery.upload(data, name: "Photo.jpg")
        XCTAssertEqual(received, data)
        XCTAssertEqual(actions, ["upload.begin", "upload.chunk", "upload.chunk", "upload.finish", "list"])
        XCTAssertNil(gallery.progress)
    }

    func testDisconnectFailsAnUploadAndAllowsARetry() async {
        let gallery = WallpaperGallery()
        gallery.connected = true; gallery.supported = true
        gallery.send = { _ in gallery.connection(false) }
        do { try await gallery.upload(Data([1]), name: "Photo"); XCTFail("Should disconnect") } catch {}
        XCTAssertNil(gallery.progress)
    }

    func testThumbnailRequestsAreDeduplicatedAndFailedRequestsCanRetry() {
        let gallery = WallpaperGallery()
        gallery.connected = true; gallery.supported = true
        var count = 0
        gallery.send = { _ in count += 1 }
        gallery.request("one"); gallery.request("one")
        XCTAssertEqual(count, 1)
        gallery.fail("Try again")
        gallery.request("one")
        XCTAssertEqual(count, 2)
    }
}
