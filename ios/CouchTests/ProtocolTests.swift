import XCTest
@testable import CouchKit

final class FrameTests: XCTestCase {
    func testParsesJSONFrame() throws {
        let f = try SessionFrame.parse(Data([0x01]) + Data(#"{"t":"pong"}"#.utf8))
        guard case .json(let d) = f else { return XCTFail("not json") }
        XCTAssertEqual(try IncomingMessage.decode(d), .pong)
    }

    func testParsesVideoConfig() throws {
        let sps = Data([0x67, 1, 2, 3]), pps = Data([0x68, 9])
        let plain = Data([0x02, 0, 4]) + sps + Data([0, 2]) + pps
        XCTAssertEqual(try SessionFrame.parse(plain), .videoConfig(sps: sps, pps: pps))
    }

    func testRejectsTruncatedVideoConfig() {
        XCTAssertThrowsError(try SessionFrame.parse(Data([0x02, 0, 9, 1, 2])))
        XCTAssertThrowsError(try SessionFrame.parse(Data([0x02])))
        XCTAssertThrowsError(try SessionFrame.parse(Data([0x02, 0, 1, 0x67, 0, 5, 1])))
    }

    func testParsesVideoUnit() throws {
        let avcc = Data.be32(3) + Data([0x65, 1, 2]) + Data.be32(2) + Data([0x41, 7])
        XCTAssertEqual(try SessionFrame.parse(Data([0x03, 0x01]) + avcc), .videoUnit(keyframe: true, avcc: avcc))
        XCTAssertEqual(try SessionFrame.parse(Data([0x03, 0x00]) + avcc), .videoUnit(keyframe: false, avcc: avcc))
    }

    func testRejectsBadVideoUnits() {
        XCTAssertThrowsError(try SessionFrame.parse(Data([0x03, 0x01])))
        XCTAssertThrowsError(try SessionFrame.parse(Data([0x03, 0x01]) + Data.be32(99) + Data([1])))
        XCTAssertThrowsError(try SessionFrame.parse(Data([0x03, 0x01]) + Data.be32(1) + Data([1, 2])))
    }

    func testRejectsUnknownAndEmpty() {
        XCTAssertThrowsError(try SessionFrame.parse(Data()))
        XCTAssertThrowsError(try SessionFrame.parse(Data([0x7F, 1])))
    }

    func testReaderSplitsAndBoundsFrames() throws {
        var r = LengthPrefixedReader(maxFrame: 8)
        let two = Data.be32(2) + Data([1, 2]) + Data.be32(1) + Data([9])
        r.feed(two.prefix(5))
        XCTAssertNil(try r.next())
        r.feed(two.dropFirst(5))
        XCTAssertEqual(try r.next(), Data([1, 2]))
        XCTAssertEqual(try r.next(), Data([9]))
        XCTAssertNil(try r.next())
        r.feed(Data.be32(9))
        XCTAssertThrowsError(try r.next())
        var zero = LengthPrefixedReader(maxFrame: 8)
        zero.feed(Data.be32(0))
        XCTAssertThrowsError(try zero.next())
    }

    func testPlainWireRoundTrip() throws {
        let wire = try PlainWire.encode(["op": .string("phone"), "n": .int(3)])
        XCTAssertEqual(wire.readU32(at: 0), UInt32(wire.count - 4))
        let obj = try PlainWire.decode(wire.dropFirst(4))
        XCTAssertEqual(obj["op"], .string("phone"))
        XCTAssertThrowsError(try PlainWire.decode(Data("[1]".utf8)))
    }

    func testConstantTimeEqual() {
        XCTAssertTrue(PhoneSession.constantTimeEqual(Data([1, 2]), Data([1, 2])))
        XCTAssertFalse(PhoneSession.constantTimeEqual(Data([1, 2]), Data([1, 3])))
        XCTAssertFalse(PhoneSession.constantTimeEqual(Data([1]), Data([1, 2])))
    }
}

final class MessageTests: XCTestCase {
    private func decode(_ s: String) throws -> IncomingMessage { try IncomingMessage.decode(Data(s.utf8)) }

    func testState() throws {
        let m = try decode(#"{"t":"state","id":"tv1","name":"Living room","model":"X","android":"14","version":"1.2","adb":"dialog","foreground":"com.x","nowPlaying":{"title":"T","artist":"A","app":"com.y"}}"#)
        guard case .state(let s) = m else { return XCTFail() }
        XCTAssertEqual(s.name, "Living room")
        XCTAssertEqual(s.adb, .dialog)
        XCTAssertNil(s.mac)
        XCTAssertEqual(s.nowPlaying?.artist, "A")
    }

    func testUnknownAdbAndTypes() throws {
        guard case .state(let s) = try decode(#"{"t":"state","id":"a","name":"b","adb":"weird"}"#) else { return XCTFail() }
        XCTAssertEqual(s.adb, .unknown)
        XCTAssertEqual(try decode(#"{"t":"future.thing","x":1}"#), .unknown("future.thing"))
        XCTAssertThrowsError(try decode(#"{"nope":1}"#))
        XCTAssertThrowsError(try decode("not json"))
    }

    func testConfigKeepsTypes() throws {
        guard case .config(let c) = try decode(#"{"t":"config","config":{"accent":"mint","clock24h":true,"ambientMinutes":5,"pinnedApps":["a","b"],"names":{"a":"A"}}}"#) else { return XCTFail() }
        XCTAssertEqual(c["accent"], .string("mint"))
        XCTAssertEqual(c["clock24h"], .bool(true))
        XCTAssertEqual(c["ambientMinutes"]?.intValue, 5)
        XCTAssertEqual(c["pinnedApps"]?.stringArray, ["a", "b"])
        XCTAssertEqual(c["names"]?.stringMap, ["a": "A"])
    }

    func testSettingsSchema() throws {
        let json = ##"{"t":"settings","groups":[{"id":"look","title":"Look","rows":[{"key":"accent","title":"Accent","kind":"swatches","options":[{"value":"mint","label":"Mint","color":"#34D399"}]},{"key":"subreddit","title":"Sub","kind":"text","visibleWhen":{"key":"wallpaper","equals":"reddit"}},{"key":"ambientMinutes","title":"Ambient","kind":"choice","options":[{"value":0,"label":"Off"},{"value":5,"label":"5 min"}]},{"key":"z","title":"Z","kind":"hologram"}]}]}"##
        guard case .settings(let g) = try decode(json) else { return XCTFail() }
        let rows = g[0].rows
        XCTAssertEqual(rows[0].options[0].color, "#34D399")
        XCTAssertFalse(rows[1].isVisible(in: ["wallpaper": .string("daily")]))
        XCTAssertTrue(rows[1].isVisible(in: ["wallpaper": .string("reddit")]))
        XCTAssertFalse(rows[1].isVisible(in: [:]))
        XCTAssertEqual(rows[2].options[1].value, .int(5))
        XCTAssertTrue(rows[2].options[1].value.matches(.double(5)))
        XCTAssertEqual(rows[3].kind, .unknown)
    }

    func testAppsInputsIconsErrors() throws {
        guard case .apps(let a) = try decode(#"{"t":"apps","apps":[{"package":"com.a","label":"A","pinned":true,"hidden":false,"banner":true}]}"#) else { return XCTFail() }
        XCTAssertEqual(a, [AppEntry(packageName: "com.a", label: "A", pinned: true, hidden: false, banner: true)])
        guard case .inputs(let i) = try decode(#"{"t":"inputs","inputs":[{"key":"hdmi1","label":"HDMI 1","icon":"hdmi","hidden":false}]}"#) else { return XCTFail() }
        XCTAssertEqual(i[0].icon, "hdmi")
        XCTAssertEqual(try decode(#"{"t":"icon","key":"com.a","png":"AQID"}"#), .icon(key: "com.a", png: Data([1, 2, 3])))
        XCTAssertEqual(try decode(#"{"t":"icon","key":"com.a"}"#), .icon(key: "com.a", png: nil))
        XCTAssertEqual(try decode(#"{"t":"error","about":"key","message":"nope"}"#), .error(about: "key", message: "nope"))
        guard case .streamStatus(let s) = try decode(#"{"t":"stream.status","state":"needs-adb","width":0,"height":0}"#) else { return XCTFail() }
        XCTAssertEqual(s.state, .needsAdb)
    }

    func testUsageAcceptsMapAndArrayShapes() throws {
        let map = #"{"t":"usage","own":{"version":1,"deviceId":"d","name":"n","generatedAt":5,"lastDay":"2026-11-07","apps":{"com.a":{"perDayMs":[1,2,3,4,5,6,7],"opens":3,"lastUsed":9,"labels":["A"]}}},"peers":[]}"#
        let arr = #"{"t":"usage","own":{"deviceId":"d","name":"n","generatedAt":5,"lastDay":"2026-11-07","apps":[{"package":"com.a","perDayMs":[1,2,3,4,5,6,7],"opens":3,"lastUsed":9}]}}"#
        for json in [map, arr] {
            guard case .usage(let u) = try decode(json) else { return XCTFail() }
            XCTAssertEqual(u.own.apps.count, 1)
            XCTAssertEqual(u.own.apps[0].packageName, "com.a")
            XCTAssertEqual(u.own.apps[0].perDayMs, [1, 2, 3, 4, 5, 6, 7])
            XCTAssertTrue(u.peers.isEmpty)
        }
    }

    func testOutgoingShapes() throws {
        func obj(_ m: OutgoingMessage) throws -> [String: JSONValue] {
            try XCTUnwrap(JSONDecoder().decode(JSONValue.self, from: m.encoded()).objectValue)
        }
        XCTAssertEqual(try obj(.hello(app: "1.0"))["t"], .string("hello"))
        XCTAssertEqual(try obj(.key(.playPause, .tap))["key"], .string("PLAY_PAUSE"))
        XCTAssertEqual(try obj(.key(.ok, .down))["action"], .string("down"))
        XCTAssertEqual(try obj(.configSet(["accent": .string("mint")]))["patch"], .object(["accent": .string("mint")]))
        XCTAssertEqual(try obj(.streamStart(.medium))["quality"], .string("medium"))
        XCTAssertEqual(try obj(.launch(packageName: "com.a"))["package"], .string("com.a"))
        XCTAssertEqual(try obj(.text(String(repeating: "a", count: 900)))["text"]?.stringValue?.count, 500)
    }
}

final class UsageMergeTests: XCTestCase {
    private func snap(_ id: String, _ lastDay: String, _ apps: [AppUsageRow]) -> UsageSnapshot {
        UsageSnapshot(deviceId: id, name: id, generatedAt: 0, lastDay: lastDay, apps: apps)
    }

    func testDayNumber() {
        XCTAssertEqual(UsageMerge.dayNumber("1970-01-01"), 0)
        XCTAssertEqual(UsageMerge.dayNumber("2026-11-08"), (UsageMerge.dayNumber("2026-11-07") ?? 0) + 1)
        XCTAssertEqual(UsageMerge.dayNumber("2024-03-01"), (UsageMerge.dayNumber("2024-02-28") ?? 0) + 2)
        XCTAssertNil(UsageMerge.dayNumber("nope"))
    }

    func testMergesByPackageAndAlignsDays() {
        let a = snap("a", "2026-11-07", [AppUsageRow(packageName: "com.x", perDayMs: [0, 0, 0, 0, 0, 0, 100], opens: 1, lastUsed: 5)])
        let b = snap("b", "2026-11-06", [AppUsageRow(packageName: "com.x", perDayMs: [0, 0, 0, 0, 0, 0, 50], opens: 2, lastUsed: 9)])
        let m = UsageMerge.merge([a, b]) { _ in "X" }
        XCTAssertEqual(m.count, 1)
        XCTAssertEqual(m[0].perDayMs, [0, 0, 0, 0, 0, 50, 100])
        XCTAssertEqual(m[0].opens, 3)
        XCTAssertEqual(m[0].lastUsed, 9)
    }

    func testMergesDifferentPackagesBySharedLabel() {
        let a = snap("a", "2026-11-07", [AppUsageRow(packageName: "ca.fibe", perDayMs: Array(repeating: 10, count: 7), opens: 1, lastUsed: 0, labels: ["Fibe TV", "Télé Fibe"])])
        let b = snap("b", "2026-11-07", [AppUsageRow(packageName: "ca.fibe2", perDayMs: Array(repeating: 5, count: 7), opens: 1, lastUsed: 0, labels: ["fibe tv"])])
        let c = snap("c", "2026-11-07", [AppUsageRow(packageName: "com.other", perDayMs: Array(repeating: 1, count: 7), opens: 1, lastUsed: 0)])
        let m = UsageMerge.merge([a, b, c]) { _ in nil }
        XCTAssertEqual(m.count, 2)
        XCTAssertEqual(m[0].packages, ["ca.fibe", "ca.fibe2"])
        XCTAssertEqual(m[0].totalMs, 105)
        XCTAssertEqual(m[1].label, "other")
    }

    func testStaleSnapshotShiftsOutOfRange() {
        let a = snap("a", "2026-11-07", [])
        let b = snap("b", "2026-10-01", [AppUsageRow(packageName: "com.x", perDayMs: Array(repeating: 9, count: 7), opens: 0, lastUsed: 0)])
        XCTAssertEqual(UsageMerge.merge([a, b]) { _ in nil }[0].totalMs, 0)
    }
}

final class CopyTests: XCTestCase {
    func testPatchHasOnlyChosenGroupsAndNeverForbiddenKeys() {
        let config: [String: JSONValue] = [
            "accent": .string("mint"), "clock24h": .bool(true), "wallpaper": .string("daily"),
            "channels": .object([:]), "hiddenInputs": .array([]), "godmode": .bool(true), "hud": .bool(true),
            "pinnedApps": .array([.string("a")]), "homeAssistantUrl": .string("http://ha"),
        ]
        let p = CopyPlan.patch(from: config, groups: [.look, .apps])
        XCTAssertEqual(Set(p.keys), ["accent", "clock24h", "pinnedApps"])
        for group in CopyGroup.allCases {
            XCTAssertTrue(Set(group.keys).isDisjoint(with: CopyPlan.neverCopied), "\(group)")
        }
        XCTAssertEqual(CopyPlan.sanitized(config).keys.contains("godmode"), false)
        XCTAssertEqual(CopyPlan.patch(from: config, groups: []), [:])
    }

    func testBackupStoreRoundTrip() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("couch-backups-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = BackupStore(url: url)
        XCTAssertTrue(store.load().isEmpty)
        let b = ConfigBackup(name: "Mine", groups: [.look], patch: ["accent": .string("mint")])
        XCTAssertEqual(store.add(b).count, 1)
        XCTAssertEqual(BackupStore(url: url).load().first?.patch["accent"], .string("mint"))
        XCTAssertTrue(store.remove(id: b.id).isEmpty)
    }

    func testMemoryStore() {
        let s = MemoryStore()
        let tv = PairedTV(id: "t", name: "TV", linkKey: Data([1]))
        s.save(tv)
        s.save(PairedTV(id: "t", name: "TV2", linkKey: Data([1])))
        XCTAssertEqual(s.load().map(\.name), ["TV2"])
        s.remove(id: "t")
        XCTAssertTrue(s.load().isEmpty)
    }
}
