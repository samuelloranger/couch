// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CouchKit",
    platforms: [.iOS("26.0"), .macOS("15.0")],
    products: [.library(name: "CouchKit", targets: ["CouchKit"])],
    targets: [
        .target(name: "CouchKit"),
    ]
)
