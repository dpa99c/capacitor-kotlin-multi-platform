# Architecture

This document explains how `capacitor-kotlin-multi-platform` turns shared Kotlin code into a Capacitor
plugin that runs on Android, iOS and the Web. It also covers why it is built this way, and its limits.

- [Overview](#overview)
- [Repository layout](#repository-layout)
- [The bridge contract](#the-bridge-contract)
- [Platform glue](#platform-glue)
- [Build pipeline](#build-pipeline)
- [How a consumer plugin builds on the base](#how-a-consumer-plugin-builds-on-the-base)
- [Compatibility decisions](#compatibility-decisions)
- [Limitations and trade-offs](#limitations-and-trade-offs)

## Overview

A Capacitor plugin normally has three independent implementations: Kotlin/Java on Android, Swift on
iOS and TypeScript on the Web. With this framework you get:

- **One shared Kotlin module** (`KmpModule`) that implements every method and emits events.
- **Platform-specific Kotlin** (`expect`/`actual`) only where a platform API is needed. Examples are
  Android `SensorManager`, iOS `CMMotionManager` (via Kotlin/Native interop) and the browser `devicemotion`
  event (via Kotlin/JS).
- **Thin generated-style glue** on each platform that forwards Capacitor calls to the Kotlin module.

```
JavaScript app
   │  MyPlugin.greet({ name })                          addListener('event', …)
   ▼
Capacitor ──────────────┬──────────────────────────────┬───────────────────────────────┐
                        │ Android                      │ iOS                           │ Web
                        ▼                              ▼                               ▼
          KmpCapacitorPlugin (Kotlin)     CAPPlugin + KmpCapacitor (Swift)   KmpWebPlugin (TS)
                        │ forward(call)                │ forward(call)                 │ invoke('greet', opts)
                        ▼                              ▼                               ▼
                    KmpBridge (JVM)            KmpBridge (Kotlin/Native)        JsKmpBridge (Kotlin/JS)
                        └──────────────┬───────────────┴───────────────┬───────────────┘
                                       ▼                               ▼
                              KmpModule (commonMain)  ──  expect/actual platform code
```

## Repository layout

```
capacitor-kotlin-multi-platform/
├── kmp/                          Gradle build of the Kotlin code (own wrapper, Gradle 8.14.3)
│   ├── build-logic/              Convention plugins io.github.capacitorkmp.library / .plugin
│   ├── gradle/libs.versions.toml Versions shared with every consumer plugin
│   └── core/                     The `core` library (KmpModule, KmpBridge, …) + CoreInfoModule
├── kmp-repo/                     ← generated: local Maven repo (core for all targets)
├── ios/Frameworks/               ← generated: CapacitorKmpCore.xcframework
├── src/generated/kmp/            ← generated: Kotlin/JS ES modules + .d.mts
├── android/                      Capacitor Android plugin (Kotlin glue)
├── ios/Sources/
│   ├── CapacitorKmpPlugin/       Capacitor iOS plugin (Swift glue)
│   └── CapacitorKmpSupport/      Swift helper for consumer plugins (KmpCapacitor)
├── src/                          TypeScript API, web implementation, KmpWebPlugin helper
├── Package.swift / *.podspec     iOS distribution (SPM binaryTarget / CocoaPods vendored_frameworks)
└── example-app/                  Vite + Capacitor app (Android, iOS)
```

Generated directories are git-ignored but listed in `package.json#files`, so they ship in the npm package.

## The bridge contract

Everything crosses a single, platform-neutral boundary of **JSON strings**:

```kotlin
class KmpBridge(module: KmpModule) {
    fun call(method: String, argsJson: String?, onSuccess: (String) -> Unit, onError: (code: String, message: String) -> Unit)
    fun setEventListener(listener: ((event: String, payloadJson: String) -> Unit)?)
    fun close()
}
```

- **Arguments.** The Capacitor call options object is serialized to JSON. In Kotlin it is available as
  a `KmpCall` with typed accessors (`requireString("name")`, `getInt(…)`) or `decode<T>()` into a
  `@Serializable` class.
- **Results.** A handler returns any `@Serializable` value. Capacitor always resolves with an object,
  so `Unit` / `null` become `{}` and non-object values become `{"value": …}`.
- **Errors.** Errors surface as `KmpException(code, message)`, which maps to `call.reject(message, code)`
  on native and to a `CapacitorException` with the same `code` on the Web. Unknown methods produce
  `UNIMPLEMENTED`, invalid options `INVALID_ARGUMENT`, and other exceptions `INTERNAL`.
- **Events.** `emit(event, payload)` inside a module calls the bridge's event listener. It maps to
  `notifyListeners` on Android and iOS and to `WebPlugin.notifyListeners` on the Web.
- **Threading.** Handlers run on the bridge's `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
  - Callbacks arrive on a background thread. Capacitor's `resolve`, `reject` and `notifyListeners` are
    thread-safe.
  - Code that needs the main thread switches explicitly, e.g. iOS UIKit uses `withContext(Dispatchers.Main)`.
  - Long-running work (streams) is launched in `KmpModule.scope`, which is cancelled by `close()`.

JSON keeps the boundary identical on all three platforms and needs no generated per-method bindings.
Each call is serialized once in each direction, which is negligible for typical plugin APIs; events
at ~25–60 Hz are fine.

## Platform glue

| Platform | Glue | Notes |
|---|---|---|
| Android | `class MyPlugin : KmpCapacitorPlugin()` with `@PluginMethod fun x(call: PluginCall) = forward(call)` | `KmpCapacitorPlugin` lives in the `core` AAR, compiled against `com.capacitorjs:core` as `compileOnly`. `load()` calls `KmpAndroid.init(context)` so `actual` code can reach `KmpAndroid.applicationContext`. |
| iOS | `CAPPlugin` subclass with `pluginMethods` and `@objc func x(_ call:) { forward(call) }` | `KmpCapacitor.forward` / `connectEvents` (Swift, `CapacitorKmpSupport`) take closures. They never reference Kotlin types, because every plugin ships its own Kotlin framework. |
| Web | `class MyPluginWeb extends KmpWebPlugin` with `x(o) { return this.invoke('x', o) }` | The Kotlin/JS module is loaded lazily with a dynamic `import()`, so it is only fetched when the plugin is first used on the Web. |

Capacitor needs every method declared natively (`@PluginMethod`, `CAPPluginMethod`) and in TypeScript
(`definitions.ts`). Those declarations are the only per-method boilerplate.

## Build pipeline

`npm run build` → `npm run kmp:build` (= `cd kmp && ./gradlew capacitorExport`) → `build:web`.

`capacitorExport` (registered by the convention plugin) depends on:

1. **`publishAllPublicationsToCapacitorKmpRepository`** publishes every KMP publication
   (`<name>`, `<name>-android`, `<name>-iosarm64`, `<name>-iossimulatorarm64`, `<name>-js`) with Gradle
   module metadata to `<plugin>/kmp-repo`. Stale versions are deleted first.
2. **`capacitorExportIos`** runs `assemble<Name>ReleaseXCFramework` and syncs the **dynamic**
   framework to `<plugin>/ios/Frameworks/<Name>.xcframework` (slices `ios-arm64`, `ios-arm64-simulator`).
3. **`capacitorExportJs`** compiles the production Kotlin/JS *executable* (no `main`, so dead-code
   elimination keeps only what `@JsExport` declarations reach). It syncs the per-module ES modules plus
   `<module>.d.mts` to `<plugin>/src/generated/kmp`.

`build:web` then runs `tsc`, which type-checks `web.ts` against the `.d.mts`. Next,
`scripts/copy-kmp-js.mjs` copies `src/generated` to `dist/esm/generated`, so the `import()` in
`dist/esm/web.js` resolves. Finally rollup bundles `dist/plugin.js` / `plugin.cjs.js`.

Each platform then consumes its artifact as follows:

- **Android.** `android/build.gradle` adds `kmp-repo` to `rootProject.allprojects.repositories` and
  depends on `io.github.capacitorkmp:core-android:<package.json version>`. The repository is scoped with
  `content { includeGroup … }`. Capacitor apps include the plugin as a Gradle subproject, so the app module
  resolves the AAR and its POM dependencies (coroutines, serialization, stdlib) without manual changes.
- **iOS / SPM.** `Package.swift` declares `.binaryTarget(path: "ios/Frameworks/CapacitorKmpCore.xcframework")`.
  Xcode embeds and signs the dynamic framework automatically.
- **iOS / CocoaPods.** The podspec uses `vendored_frameworks`.
- **Web.** `web.ts` runs `import('./generated/kmp/capacitor-kmp-core.mjs')`. App bundlers (Vite,
  webpack, …) code-split it.

## How a consumer plugin builds on the base

A consumer plugin (see `capacitor-kotlin-multi-platform-consumer`) depends on the base through npm
(`peerDependency`). Its own `kmp/settings.gradle.kts` uses three things from
`node_modules/capacitor-kotlin-multi-platform`:

```kotlin
pluginManagement { includeBuild("../node_modules/capacitor-kotlin-multi-platform/kmp/build-logic") }
dependencyResolutionManagement {
    repositories { maven { url = uri("../node_modules/capacitor-kotlin-multi-platform/kmp-repo") } /* … */ }
    versionCatalogs { create("libs") { from(files("../node_modules/capacitor-kotlin-multi-platform/kmp/gradle/libs.versions.toml")) } }
}
```

- **The convention plugin.** `io.github.capacitorkmp.plugin` adds `api("io.github.capacitorkmp:core:<base version>")`.
  The base version is baked into the build-logic from the base `package.json`. It also `export`s `core`
  into the consumer's XCFramework, so Swift can use `KmpBridge`.
- **The prebuilt `core`** for every target, from `kmp-repo`. KMP libraries are *not* consumed via
  `includeBuild` because composite builds of KMP libraries still don't resolve iOS/JS variants reliably
  (KT-52172).
- **The shared version catalog**, so the consumer compiles with the same Kotlin version as `core`.
  Kotlin/Native and Kotlin/JS klibs require a compatible compiler.

The base also takes part at runtime on each platform:

- **Android.** The consumer's Android module has `implementation project(':capacitor-kotlin-multi-platform')`.
  The base project registers the `core` repository and provides `KmpCapacitorPlugin`. The consumer's AAR
  depends on `core-android` through its POM.
- **iOS.** The consumer's `Package.swift` depends on the base package
  (`.package(name: "CapacitorKotlinMultiPlatform", path: "../capacitor-kotlin-multi-platform")`) for the
  `CapacitorKmpSupport` helper. The podspec uses `s.dependency 'CapacitorKotlinMultiPlatform'`.
- **Web.** `web.ts` imports `KmpWebPlugin` from `capacitor-kotlin-multi-platform`.

Apps must list **both** packages as dependencies, because the Capacitor CLI only links native code of
direct dependencies:

```bash
npm install capacitor-kotlin-multi-platform capacitor-kotlin-multi-platform-consumer
```

### Umbrella variant

[`capacitor-kotlin-multi-platform-umbrella`](../../capacitor-kotlin-multi-platform-umbrella) uses the
same three build-time inputs: the convention plugin, the prebuilt `core` and the version catalog. It
differs in four ways:

- Its features are modules of **one** KMP build.
- An `:umbrella` module links them, together with `core`, into the only XCFramework and JS module.
- `scripts/sync-from-base.mjs` copies the base's `core` artifacts and Swift/TS helpers into the package,
  so the base is only a `devDependency`.
- Apps install just the umbrella package, which registers several Capacitor plugins (`KmpDevice`,
  `KmpMotion`). The app ends up with one Kotlin runtime on iOS and one Kotlin/JS module on the Web.

## Compatibility decisions

These were validated with a Capacitor 8.6 app on Android 16 (API 36) and iOS 26.5 / 27.2 simulators,
built with Xcode 27.2.

### Kotlin version vs. the Capacitor app's Kotlin

**Problem.** Capacitor apps compile every plugin's `android/` folder inside the *app's* Gradle build,
with the app's Kotlin version (2.2.20 by default in Capacitor 8). That compiler can read Kotlin metadata
at most one minor version ahead. A library built with Kotlin 2.4, or one that drags `kotlin-stdlib:2.4.x`
onto the app's classpath, would break the app's Kotlin compilation.

The convention plugin handles this as follows:

- The KMP code is built in its own Gradle build (`kmp/`) with **Kotlin 2.4.21**, which has the newest
  Kotlin/Native and Xcode support.
- `languageVersion = apiVersion = 2.2` for all compilations, so the metadata is readable by Kotlin 2.2+.
- `kotlin.stdlib.default.dependency=false` plus explicit stdlib dependencies:
  - common/Android: `kotlin-stdlib:2.2.20`, which is what the POM advertises
  - JS: `kotlin-stdlib:2.4.21`, because Kotlin/JS requires an ABI-matching stdlib
  - Native: always uses the compiler's bundled stdlib
- kotlinx libraries are pinned to versions built against stdlib ≤ 2.2.x: coroutines 1.11.0 and
  serialization 1.9.0.
- The Android JVM target is 17.

On Android, `getKmpInfo()` reports `kotlinVersion: 2.4.21` (compiler) and `kotlinStdlibVersion: 2.2.20` (runtime).

### Validation results (de-risking spike)

| Assumption | Result |
|---|---|
| Kotlin 2.4.21 links iOS frameworks with Xcode 27.2 | ✅ Works. `kotlin.apple.xcodeCompatibility.nowarn=true` silences the "untested Xcode" warning. |
| AAR built with Kotlin 2.4 at language level 2.2 compiles in a Capacitor 8 app (Kotlin 2.2.20) with Kotlin glue | ✅ |
| Repository injection from a plugin's `build.gradle` works with the Capacitor 8 app template | ✅ No app changes needed. It fails only if an app sets `RepositoriesMode.FAIL_ON_PROJECT_REPOS`; in that case add the `kmp-repo` folders to `settings.gradle` manually. |
| SPM: consumer → base `.package(path:)` resolves next to Capacitor's generated CapApp-SPM, also with `file:` links | ✅ Both paths resolve to the same directory. |
| CocoaPods: `pod lib lint` of both podspecs | ✅ With `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64` (see limitations). |

### Web plugin factory must be memoised

Capacitor's `registerPlugin` web loader is not race-free. When several plugin calls (e.g. a few
`addListener`s and a method call) are made before the first `import('./web')` resolves, each one creates
its own web instance. With KMP each instance hosts its own Kotlin module, so events end up on an
instance nobody listens to. The plugins therefore memoise the factory:

```ts
let web: Promise<MyPluginWeb> | undefined;
registerPlugin<MyPlugin>('MyPlugin', { web: () => (web ??= import('./web').then((m) => new m.MyPluginWeb())) });
```

## Limitations and trade-offs

- **One Kotlin runtime per iOS framework.** Each KMP plugin ships its own dynamic XCFramework. Each
  framework embeds its own Kotlin/Native runtime, stdlib, kotlinx libraries and copy of `core`. That
  baseline is **≈ 2.7 MB stripped (≈ 0.8 MB compressed) per framework**, while the plugin's own code is
  typically only tens to hundreds of KB. An app using the base plus one consumer embeds two frameworks
  (`CapacitorKmpCore`, `KmpConsumerShared`). The base plus N plugins embeds N + 1.

  The frameworks work side by side, because their ObjC class names are prefixed with the framework
  name. But Kotlin objects can't be passed between plugins' frameworks, and each runtime has its own
  heap, GC and thread pool. No build setting shares a runtime between separately compiled Kotlin
  frameworks. The fix is to compile all features into **one umbrella framework** by extending the base
  instead of adding plugins next to it. See
  [EXTENDING.md › Alternative: one umbrella framework per app](EXTENDING.md#alternative-one-umbrella-framework-per-app),
  which also covers the trade-offs. The reference implementation is
  [`capacitor-kotlin-multi-platform-umbrella`](../../capacitor-kotlin-multi-platform-umbrella): the same
  features as the consumer, with 3.2 MB of Kotlin framework instead of 5.9 MB. The convention plugin supports this directly: only the module that
  declares `iosFramework(…)` exports a framework and JS module, while feature modules just publish to
  `kmp-repo`.
- **Duplicated Kotlin/JS code on the Web.** Each plugin's JS output includes the Kotlin stdlib,
  coroutines and serialization. That is about 820 KB unminified, ~140 KB gzipped, for `core`. The chunk
  is loaded lazily, only when the plugin is used on the Web. The umbrella approach produces a single JS
  module.
- **Same Kotlin version for base and consumers.** Consumers inherit the base's version catalog. Upgrading
  Kotlin means releasing a new base version and rebuilding consumers.
- **No x86_64 iOS simulator.** Only `iosArm64` and `iosSimulatorArm64` are built. Intel-Mac simulators
  are unsupported, and the podspecs exclude `x86_64` simulator builds.
- **Per-method declarations.** Capacitor still needs each method listed natively and in TypeScript. The
  bridge validates everything else at runtime: unknown methods produce `UNIMPLEMENTED`.
- **Android `Dispatchers.Main`.** `kotlinx-coroutines-android` is not included. Android `actual` code
  that needs the main thread should use a `Handler(Looper.getMainLooper())` or add that dependency.
- **Building plugins needs a Mac** with Xcode (for the XCFramework) and a JDK. Apps consuming published
  plugins don't, because the artifacts are prebuilt.
