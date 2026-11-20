// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorKotlinMultiPlatform",
    platforms: [.iOS(.v15)],
    products: [
        // The base plugin itself (JS name: `CapacitorKmp`).
        .library(
            name: "CapacitorKotlinMultiPlatform",
            targets: ["CapacitorKmpPlugin"]),
        // Swift helpers for plugins built on capacitor-kotlin-multi-platform.
        .library(
            name: "CapacitorKmpSupport",
            targets: ["CapacitorKmpSupport"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        // Shared Kotlin code, built by `npm run kmp:build` (kmp/core).
        .binaryTarget(
            name: "CapacitorKmpCore",
            path: "ios/Frameworks/CapacitorKmpCore.xcframework"),
        .target(
            name: "CapacitorKmpSupport",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/CapacitorKmpSupport"),
        .target(
            name: "CapacitorKmpPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                "CapacitorKmpSupport",
                "CapacitorKmpCore"
            ],
            path: "ios/Sources/CapacitorKmpPlugin"),
        .testTarget(
            name: "CapacitorKmpPluginTests",
            dependencies: ["CapacitorKmpPlugin", "CapacitorKmpSupport", "CapacitorKmpCore"],
            path: "ios/Tests/CapacitorKmpPluginTests")
    ]
)
