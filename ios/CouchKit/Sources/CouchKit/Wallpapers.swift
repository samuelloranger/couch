import Foundation

public struct WallpaperEntry: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var bytes: Int
    public init(id: String, name: String, bytes: Int) { self.id = id; self.name = name; self.bytes = bytes }
}

public struct WallpaperUploadAck: Codable, Equatable, Sendable {
    public var id: String
    public var offset: Int
    public var done: Bool
    public init(id: String, offset: Int, done: Bool) { self.id = id; self.offset = offset; self.done = done }
}

public enum WallpaperWire {
    public static let maxBytes = 20 * 1024 * 1024
    public static let chunkBytes = 64 * 1024

    public static func chunk(_ data: Data, offset: Int) -> Data? {
        guard offset >= 0, offset < data.count, data.count <= maxBytes else { return nil }
        return data.subdata(in: offset..<min(offset + chunkBytes, data.count))
    }
}
