import SwiftUI
import UIKit

enum Theme {
    /// Couch mint, #34D399.
    static let accent = Color(red: 0x34 / 255, green: 0xD3 / 255, blue: 0x99 / 255)
    static let background = Color(red: 0x0B / 255, green: 0x0F / 255, blue: 0x0E / 255)
    static let card = Color.white.opacity(0.06)

    static func color(hex: String?) -> Color? {
        guard var h = hex?.trimmingCharacters(in: CharacterSet(charactersIn: "#")), h.count == 6 || h.count == 8 else { return nil }
        if h.count == 8 { h = String(h.suffix(6)) }
        guard let v = UInt32(h, radix: 16) else { return nil }
        return Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

@MainActor
enum Haptics {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func press() { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
    static func select() { UISelectionFeedbackGenerator().selectionChanged() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func failure() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
}

enum Format {
    /// "2 h 5 min", "14 min", "< 1 min".
    static func duration(ms: Int64) -> String {
        let minutes = Int(ms / 60_000)
        if minutes <= 0 { return ms > 0 ? String(localized: "< 1 min") : "0 min" }
        if minutes < 60 { return "\(minutes) min" }
        let m = minutes % 60
        return m == 0 ? "\(minutes / 60) h" : "\(minutes / 60) h \(m) min"
    }
}

/// Decoded icon images, so a grid scroll doesn't decode PNGs again.
@MainActor
enum IconCache {
    private static let cache = NSCache<NSString, UIImage>()
    static func image(key: String, data: Data) -> UIImage? {
        if let hit = cache.object(forKey: key as NSString) { return hit }
        guard let image = UIImage(data: data) else { return nil }
        cache.setObject(image, forKey: key as NSString)
        return image
    }
    static func drop(key: String) { cache.removeObject(forKey: key as NSString) }
}
