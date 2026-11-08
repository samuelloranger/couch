import Foundation
import Network

public struct DiscoveredTV: Sendable, Identifiable, Hashable {
    public var id: String
    public var name: String
    /// True while the TV has "Pair a phone" open.
    public var pairing: Bool
    public var endpoint: NWEndpoint

    public init(id: String, name: String, pairing: Bool, endpoint: NWEndpoint) {
        self.id = id; self.name = name; self.pairing = pairing; self.endpoint = endpoint
    }
}

/// Bonjour browse for `_couch._tcp`. The TV's TXT record carries `id`, `name` and `pair` ("1" while pairing is open).
public final class TVBrowser: @unchecked Sendable {
    public static let serviceType = "_couch._tcp"
    private var browser: NWBrowser?
    private let queue = DispatchQueue(label: "cloud.samlo.couch.browser")

    public init() {}

    /// Starts browsing; `onChange` gets the whole current list each time it changes.
    public func start(onChange: @escaping @Sendable ([DiscoveredTV]) -> Void) {
        stop()
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { results, _ in
            onChange(Self.parse(results))
        }
        b.start(queue: queue)
        browser = b
    }

    public func stop() {
        browser?.cancel()
        browser = nil
    }

    static func parse(_ results: Set<NWBrowser.Result>) -> [DiscoveredTV] {
        var out: [String: DiscoveredTV] = [:]
        for r in results {
            var id = ""
            var name = ""
            var pairing = false
            if case .service(let serviceName, _, _, _) = r.endpoint { id = serviceName; name = serviceName }
            if case .bonjour(let txt) = r.metadata {
                if let v = txt["id"], !v.isEmpty { id = v }
                if let v = txt["name"], !v.isEmpty { name = v }
                pairing = txt["pair"] == "1"
            }
            guard !id.isEmpty else { continue }
            out[id] = DiscoveredTV(id: id, name: name, pairing: pairing, endpoint: r.endpoint)
        }
        return out.values.sorted {
            if $0.pairing != $1.pairing { return $0.pairing }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }
}
