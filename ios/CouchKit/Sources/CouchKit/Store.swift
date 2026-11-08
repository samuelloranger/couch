import Foundation
import Security

public enum StoreError: Error, Equatable { case keychain(Int32) }

public struct PairedTV: Sendable, Codable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var linkKey: Data
    public var lastHost: String?
    public var mac: String?

    public init(id: String, name: String, linkKey: Data, lastHost: String? = nil, mac: String? = nil) {
        self.id = id; self.name = name; self.linkKey = linkKey; self.lastHost = lastHost; self.mac = mac
    }
}

/// Where paired TVs and this phone's identity live.
public protocol PairedStore: Sendable {
    func load() -> [PairedTV]
    func save(_ tv: PairedTV) throws
    func remove(id: String)
    /// A stable random id for this phone, created on first use.
    func phoneId() -> String
}

public final class MemoryStore: PairedStore, @unchecked Sendable {
    private let lock = NSLock()
    private var tvs: [PairedTV]
    private let id: String

    public init(tvs: [PairedTV] = [], phoneId: String = UUID().uuidString) { self.tvs = tvs; self.id = phoneId }

    public func load() -> [PairedTV] { lock.lock(); defer { lock.unlock() }; return tvs }
    public func save(_ tv: PairedTV) throws {
        lock.lock(); defer { lock.unlock() }
        if let i = tvs.firstIndex(where: { $0.id == tv.id }) { tvs[i] = tv } else { tvs.append(tv) }
    }
    public func remove(id: String) { lock.lock(); defer { lock.unlock() }; tvs.removeAll { $0.id == id } }
    public func phoneId() -> String { id }
}

/// Keychain-backed store: one generic-password item per TV (the item holds the JSON of the `PairedTV`).
public struct KeychainStore: PairedStore {
    private let service: String

    public init(service: String = "cloud.samlo.couch") { self.service = service }

    private func base(account: String, kind: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "\(service).\(kind)",
            kSecAttrAccount as String: account,
        ]
    }

    @discardableResult
    private func write(_ data: Data, account: String, kind: String) -> OSStatus {
        var query = base(account: account, kind: kind)
        let update = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        guard status == errSecItemNotFound else { return status }
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(query as CFDictionary, nil)
    }

    private func read(account: String, kind: String) -> Data? {
        var query = base(account: account, kind: kind)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    public func load() -> [PairedTV] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "\(service).tv",
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        query[kSecReturnAttributes as String] = false
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess, let items = out as? [Data] else { return [] }
        return items.compactMap { try? JSONDecoder().decode(PairedTV.self, from: $0) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    public func save(_ tv: PairedTV) throws {
        let data = try JSONEncoder().encode(tv)
        let status = write(data, account: tv.id, kind: "tv")
        guard status == errSecSuccess else { throw StoreError.keychain(status) }
    }

    public func remove(id: String) {
        SecItemDelete(base(account: id, kind: "tv") as CFDictionary)
    }

    public func phoneId() -> String {
        if let data = read(account: "phone", kind: "identity"), let s = String(data: data, encoding: .utf8), !s.isEmpty { return s }
        let fresh = UUID().uuidString.lowercased()
        write(Data(fresh.utf8), account: "phone", kind: "identity")
        return fresh
    }
}
