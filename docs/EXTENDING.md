# Extending: build your own Kotlin Multiplatform Capacitor plugin

This guide shows how to create a new Capacitor plugin whose logic is written once in Kotlin, on top of
`capacitor-kotlin-multi-platform`. It ends with how to customise the base plugin itself.

The complete, working reference for everything here is
[`capacitor-kotlin-multi-platform-consumer`](../../capacitor-kotlin-multi-platform-consumer). Copying
from it is the fastest way to start.

- [1. Scaffold a Capacitor plugin](#1-scaffold-a-capacitor-plugin)
- [2. Add the KMP build](#2-add-the-kmp-build)
- [3. Write the shared module](#3-write-the-shared-module)
- [4. Platform-specific code with expect/actual](#4-platform-specific-code-with-expectactual)
- [5. Wire up Android](#5-wire-up-android)
- [6. Wire up iOS](#6-wire-up-ios)
- [7. Wire up the Web](#7-wire-up-the-web)
- [8. Build, test, run](#8-build-test-run)
- [Checklist: adding a method](#checklist-adding-a-method)
- [Events](#events)
- [Errors](#errors)
- [Threading and lifecycle](#threading-and-lifecycle)
- [Permissions](#permissions)
- [Testing](#testing)
- [Publishing](#publishing)
- [Alternative: one umbrella framework per app](#alternative-one-umbrella-framework-per-app)
- [Customising the base plugin](#customising-the-base-plugin)
- [Troubleshooting](#troubleshooting)

> **Two ways to structure KMP plugins.**
>
> - **Multi-plugin** (steps 1–8): every feature is its own Capacitor plugin built on the base. It is
>   modular and independently versioned, but on iOS each plugin embeds its own Kotlin runtime
>   (≈ 2.7 MB per plugin). Reference:
>   [`capacitor-kotlin-multi-platform-consumer`](../../capacitor-kotlin-multi-platform-consumer).
> - **Umbrella**: all features in one KMP build, so one framework and one runtime. Use it when an app
>   needs several KMP-powered features; see
>   [Alternative: one umbrella framework per app](#alternative-one-umbrella-framework-per-app). Reference:
>   [`capacitor-kotlin-multi-platform-umbrella`](../../capacitor-kotlin-multi-platform-umbrella).

Prerequisites: Node 20+, JDK 17+ (21 recommended), Xcode with the iOS SDK, Android SDK. Gradle, Kotlin
and Kotlin/Native are downloaded by the Gradle wrapper on the first build (~1 GB, into `~/.gradle` and
`~/.konan`).

## 1. Scaffold a Capacitor plugin

```bash
npm init @capacitor/plugin@latest my-plugin -- \
  --name my-plugin --package-id com.example.myplugin --class-name MyPlugin --android-lang kotlin
cd my-plugin
npm install --save-dev capacitor-kotlin-multi-platform   # or file:../capacitor-kotlin-multi-platform
```

In `package.json`:

```jsonc
{
  "peerDependencies": {
    "@capacitor/core": ">=8.0.0",
    "capacitor-kotlin-multi-platform": "^0.1.0"
  },
  "files": [
    "android/src/main/", "android/build.gradle", "dist/",
    "ios/Sources", "ios/Tests", "ios/Frameworks/",   // ← XCFramework
    "kmp-repo/",                                      // ← Android artifacts
    "Package.swift", "MyPlugin.podspec"
  ],
  "scripts": {
    "kmp:build": "cd kmp && ./gradlew capacitorExport",
    "kmp:test": "cd kmp && ./gradlew allTests",
    "build": "npm run kmp:build && npm run build:web",
    "build:web": "npm run clean && npm run docgen && tsc && node scripts/copy-kmp-js.mjs && rollup -c rollup.config.mjs"
  }
}
```

Copy [`scripts/copy-kmp-js.mjs`](../scripts/copy-kmp-js.mjs) from this package. Add the generated folders
to `.gitignore`, and add `src/generated` and `kmp` to `.eslintignore` / `.prettierignore`:

```gitignore
/kmp-repo
/ios/Frameworks
/src/generated
.kotlin
```

## 2. Add the KMP build

```
my-plugin/kmp/
├── gradlew, gradlew.bat, gradle/wrapper/   ← copy from node_modules/capacitor-kotlin-multi-platform/kmp
├── gradle.properties                        ← copy as well
├── settings.gradle.kts
└── shared/
    ├── build.gradle.kts
    └── src/{commonMain,commonTest,androidMain,iosMain,jsMain}/kotlin/…
```

**`kmp/settings.gradle.kts`** uses the build-logic, prebuilt `core` and versions from the base package:

```kotlin
pluginManagement {
    includeBuild("../node_modules/capacitor-kotlin-multi-platform/kmp/build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("../node_modules/capacitor-kotlin-multi-platform/kmp-repo")
            content { includeGroup("io.github.capacitorkmp") }
        }
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../node_modules/capacitor-kotlin-multi-platform/kmp/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "my-plugin-kmp"
include(":shared")
```

**`kmp/shared/build.gradle.kts`**:

```kotlin
plugins {
    id("io.github.capacitorkmp.plugin")
}

group = "com.example.myplugin"          // Maven group of the exported artifacts

capacitorKmp {
    android(namespace = "com.example.myplugin.shared")
    iosFramework("MyPluginShared")        // → ios/Frameworks/MyPluginShared.xcframework, Swift module name
    jsModule("my-plugin-shared")          // → src/generated/kmp/my-plugin-shared.mjs
}

// Optional extra dependencies
kotlin {
    sourceSets {
        commonMain.dependencies { /* implementation("…") */ }
    }
}
```

The convention plugin provides the following, so you don't configure them yourself:

- targets: `androidLibrary`, `iosArm64`, `iosSimulatorArm64` and `js` (IR, ES modules, `.d.mts`)
- `api` dependencies on `core`, kotlinx-coroutines and kotlinx-serialization, plus the serialization
  compiler plugin
- `kotlin-test` and `kotlinx-coroutines-test` in `commonTest`
- the Android compatibility settings (see [ARCHITECTURE.md](ARCHITECTURE.md#compatibility-decisions))
- the `capacitorExport` task

The Maven version is the `version` from your `package.json`.

## 3. Write the shared module

`kmp/shared/src/commonMain/kotlin/com/example/myplugin/MyModule.kt`:

```kotlin
package com.example.myplugin

import io.github.capacitorkmp.core.KmpErrorCode
import io.github.capacitorkmp.core.KmpException
import io.github.capacitorkmp.core.KmpModule
import io.github.capacitorkmp.core.KmpPlatform
import kotlinx.serialization.Serializable

class MyModule(private val clock: Clock) : KmpModule() {

    constructor() : this(platformClock())          // no-arg constructor: used by Swift & the glue

    init {
        // Typed options via decode<T>(), result serialized automatically.
        method("greet") {
            val options = decode<GreetOptions>()
            if (options.name.isBlank()) throw KmpException(KmpErrorCode.INVALID_ARGUMENT, "name must not be empty")
            Greeting("Hello ${options.name} from ${KmpPlatform.name}", clock.now())
        }

        // Untyped accessors work too: requireString / getInt / getBoolean / getObject …
        method("add") { Sum(requireInt("a") + requireInt("b")) }

        // Return Unit for "no data" (resolves with {}). Handlers that only throw need an explicit
        // type: method<Unit>("x") { throw … }
        method("reset") { }
    }
}

@Serializable data class GreetOptions(val name: String, val excited: Boolean = false)
@Serializable data class Greeting(val message: String, val timestamp: Long)
@Serializable data class Sum(val sum: Int)
```

A few rules apply to modules:

- Method names must match the names you declare to Capacitor (TS, Android, iOS).
- Results should be `@Serializable` classes (or `Unit`). Capacitor resolves with objects, so primitives
  are wrapped as `{ "value": … }`.
- Keep platform access behind interfaces you can fake in tests. Inject them through the constructor and
  provide a no-arg constructor that uses the `actual` implementations.

## 4. Platform-specific code with expect/actual

Declare what you need from the platform in `commonMain`, ideally as an interface plus an `expect`
factory:

```kotlin
// commonMain
fun interface Clock { fun now(): Long }
expect fun platformClock(): Clock
```

Then implement it in each platform source set:

```kotlin
// androidMain – full Android SDK; get a Context from KmpAndroid
actual fun platformClock(): Clock = Clock { System.currentTimeMillis() }
//   val sensors = KmpAndroid.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

// iosMain – Apple frameworks via Kotlin/Native interop (platform.Foundation, platform.UIKit, platform.CoreMotion …)
actual fun platformClock(): Clock = Clock { (NSDate().timeIntervalSince1970 * 1000).toLong() }

// jsMain – browser APIs via `dynamic` / `js("…")` or kotlinx-browser
actual fun platformClock(): Clock = Clock { (js("Date.now()") as Double).toLong() }
```

Some patterns used in the consumer plugin:

| Need | Android | iOS | Web |
|---|---|---|---|
| Callback-based stream → `Flow` | `callbackFlow { registerListener(…); awaitClose { unregister } }` | `callbackFlow { manager.startUpdates(queue) { … trySend(…) }; awaitClose { stop() } }` | `callbackFlow { window.addEventListener(…); awaitClose { removeEventListener } }` |
| Main thread APIs | `Handler(Looper.getMainLooper())` | `withContext(Dispatchers.Main) { UIDevice… }` | single-threaded |
| Await a platform async API | `suspendCancellableCoroutine` | `suspendCancellableCoroutine` | `(promise as Promise<T>).await()` |
| C structs (iOS) | – | `cValue.useContents { x }`, `memScoped { alloc<…>() }` | – |

Normalise platform differences (units, axis conventions, naming) in **shared** code, not in the
`actual`s. Then the logic is written once and unit tested once. For example, the consumer's
`MotionNormalizer` converts CoreMotion's g-units and inverted axes to m/s².

## 5. Wire up Android

**`android/build.gradle`.** Next to the template content, register your Maven repo and add the
dependencies:

```groovy
repositories { google(); mavenCentral() }

def myKmpRepo = file("../kmp-repo")
rootProject.allprojects { p ->
    p.repositories {
        maven { url = myKmpRepo; content { includeGroup "com.example.myplugin" } }
    }
}
def myVersion = new groovy.json.JsonSlurper().parse(file("../package.json")).version

dependencies {
    implementation project(':capacitor-android')
    implementation project(':capacitor-kotlin-multi-platform')            // base: core + its repository
    implementation "com.example.myplugin:shared-android:$myVersion"       // your shared code
    // …template dependencies
}
```

**`android/settings.gradle`** is for building the plugin on its own (`npm run verify:android`). Add the
base project:

```groovy
include ':capacitor-kotlin-multi-platform'
project(':capacitor-kotlin-multi-platform').projectDir = new File('../node_modules/capacitor-kotlin-multi-platform/android')
```

**The plugin class** replaces the template's implementation class:

```kotlin
@CapacitorPlugin(name = "MyPlugin")
class MyPluginPlugin : KmpCapacitorPlugin() {
    override fun createModule(): KmpModule = MyModule()

    @PluginMethod fun greet(call: PluginCall) = forward(call)
    @PluginMethod fun add(call: PluginCall) = forward(call)
    @PluginMethod fun reset(call: PluginCall) = forward(call)
}
```

`KmpCapacitorPlugin` initialises `KmpAndroid`, hosts the module, forwards events to `notifyListeners`
and closes the bridge in `handleOnDestroy`. You can still use the full Capacitor Android API in this
class, e.g. `@CapacitorPlugin(permissions = …)`, `getActivity()`, or overriding `load()` (call `super.load()`).

## 6. Wire up iOS

**`Package.swift`**:

```swift
dependencies: [
    .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0"),
    .package(name: "CapacitorKotlinMultiPlatform", path: "../capacitor-kotlin-multi-platform")
],
targets: [
    .binaryTarget(name: "MyPluginShared", path: "ios/Frameworks/MyPluginShared.xcframework"),
    .target(
        name: "MyPluginPlugin",
        dependencies: [
            .product(name: "Capacitor", package: "capacitor-swift-pm"),
            .product(name: "Cordova", package: "capacitor-swift-pm"),
            .product(name: "CapacitorKmpSupport", package: "CapacitorKotlinMultiPlatform"),
            "MyPluginShared"
        ],
        path: "ios/Sources/MyPluginPlugin"),
    // …tests
]
```

`../capacitor-kotlin-multi-platform` works because npm installs both packages next to each other in
`node_modules`. In development with a sibling checkout, the path resolves to the same directory.

**Podspec**:

```ruby
s.vendored_frameworks = 'ios/Frameworks/MyPluginShared.xcframework'
s.pod_target_xcconfig = { 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64' }
s.user_target_xcconfig = { 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64' }
s.dependency 'Capacitor'
s.dependency 'CapacitorKotlinMultiPlatform'
```

**The plugin class**:

```swift
import Capacitor
import MyPluginShared
#if canImport(CapacitorKmpSupport)
import CapacitorKmpSupport            // SPM
#else
import CapacitorKotlinMultiPlatform   // CocoaPods
#endif

@objc(MyPluginPlugin)
public class MyPluginPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MyPluginPlugin"
    public let jsName = "MyPlugin"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "greet", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "add", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reset", returnType: CAPPluginReturnPromise)
    ]

    private lazy var kmp = KmpBridge(module: MyModule())

    override public func load() {
        KmpCapacitor.connectEvents(of: self) { listener in kmp.setEventListener(listener: listener) }
    }

    deinit { kmp.close() }

    @objc func greet(_ call: CAPPluginCall) { forward(call) }
    @objc func add(_ call: CAPPluginCall) { forward(call) }
    @objc func reset(_ call: CAPPluginCall) { forward(call) }

    private func forward(_ call: CAPPluginCall) {
        KmpCapacitor.forward(call) { method, args, onSuccess, onError in
            kmp.call(method: method, argsJson: args, onSuccess: onSuccess, onError: onError)
        }
    }
}
```

`KmpBridge`, `MyModule` and every public Kotlin class are visible in Swift because the convention plugin
exports `core` into your framework. Kotlin default arguments are not visible from Swift, which is why
modules need a no-arg constructor.

## 7. Wire up the Web

`src/web.ts`:

```ts
import { KmpWebPlugin } from 'capacitor-kotlin-multi-platform';
import type { Greeting, GreetOptions, MyPlugin } from './definitions';

export class MyPluginWeb extends KmpWebPlugin implements MyPlugin {
  constructor() {
    super(() => import('./generated/kmp/my-plugin-shared.mjs').then((m) => m.createMyPluginBridge()));
  }
  greet(options: GreetOptions): Promise<Greeting> {
    return this.invoke('greet', options);
  }
  // …
}
```

Export the factory from `jsMain`:

```kotlin
@JsExport
fun createMyPluginBridge(): JsKmpBridge = MyModule().toJsBridge()
```

In `src/index.ts`, **memoise the web factory**. Capacitor may otherwise create several web instances,
each hosting its own Kotlin module:

```ts
let web: Promise<MyPluginWeb> | undefined;
const MyPlugin = registerPlugin<MyPluginPlugin>('MyPlugin', {
  web: () => (web ??= import('./web').then((m) => new m.MyPluginWeb())),
});
```

In `rollup.config.mjs`, mark the base as external:

```js
external: ['@capacitor/core', 'capacitor-kotlin-multi-platform'],
output: [{ /* iife */ globals: { '@capacitor/core': 'capacitorExports', 'capacitor-kotlin-multi-platform': 'capacitorCapacitorKmp' } }, /* … */],
```

## 8. Build, test, run

```bash
npm run kmp:test      # shared tests on Android host (JVM), iOS simulator and Node
npm run build         # Gradle capacitorExport + TypeScript/rollup
npm run verify        # xcodebuild + Gradle build + web build

# example app: depend on BOTH packages so Capacitor links both native parts
cd example
npm install capacitor-kotlin-multi-platform@file:../../capacitor-kotlin-multi-platform my-plugin@file:..
npm run build && npx cap sync && npx cap run android
```

After changing Kotlin code, run `npm run kmp:build` (or `npm run build`) in the plugin, then
`npx cap sync` in the app.

## Checklist: adding a method

1. **Kotlin.** Add `method("doThing") { … }` in your module, plus any `@Serializable` option and result
   classes and `actual`s.
2. **Tests.** Cover it in `commonTest`.
3. **TypeScript.** Add `doThing(options: DoThingOptions): Promise<DoThingResult>` to `definitions.ts`
   with JSDoc for docgen.
4. **Web.** Add `doThing(o) { return this.invoke('doThing', o); }` to `web.ts`.
5. **Android.** Add `@PluginMethod fun doThing(call: PluginCall) = forward(call)`.
6. **iOS.** Add `CAPPluginMethod(name: "doThing", …)` and `@objc func doThing(_ call: CAPPluginCall) { forward(call) }`.
7. **Build.** Run `npm run build`, then `npx cap sync` in the app.

If you forget a native declaration, Capacitor reports "not implemented". If the Kotlin side is missing,
the call rejects with `UNIMPLEMENTED`.

## Events

```kotlin
emit("progress", Progress(percent = 42))   // inside a module (any thread)
```

```ts
addListener(eventName: 'progress', listenerFunc: (p: Progress) => void): Promise<PluginListenerHandle>;
```

Events go to `notifyListeners` on every platform, and nothing else is required. Long-running producers
should run in `scope.launch { … }`; `scope` is cancelled when the plugin is destroyed. Keep the job in a
field so a `stop…` method can `cancelAndJoin()` it. `ConsumerModule.startMotionUpdates` shows the full
pattern, including throttling high-frequency events.

## Errors

```kotlin
throw KmpException("NOT_FOUND", "No item with id $id")
```

JavaScript receives a rejected promise with `error.code === 'NOT_FOUND'` and `error.message`. The
framework uses these codes:

| Code | When |
|---|---|
| `UNIMPLEMENTED` | No handler is registered for the method |
| `INVALID_ARGUMENT` | `decode<T>()` or `require*()` failed, or the options are not valid JSON |
| `UNAVAILABLE` | Use it for missing hardware or features |
| `PERMISSION_DENIED` | Use it for denied permissions |
| `CANCELLED` | The call was cancelled because the bridge closed |
| `INTERNAL` | Any other exception (its message is passed through) |

## Threading and lifecycle

- Handlers run on `Dispatchers.Default`, and they are suspend functions.
- Switch to the main thread only where a platform API requires it. On iOS use
  `withContext(Dispatchers.Main)`. On Android use a main-thread `Handler`, or add
  `kotlinx-coroutines-android` to `androidMain` to get `Dispatchers.Main`.
- `KmpModule.onAttach()` / `onClose()` are lifecycle hooks; override `onClose()` to release resources.
- One module instance exists per plugin instance, so its state lives as long as the app's WebView.

## Permissions

Permission handling is platform specific:

- **Android runtime permissions.** Declare them on the plugin with
  `@CapacitorPlugin(permissions = [Permission(alias = "location", strings = [...])])`. Use Capacitor's
  `requestPermissionForAlias` in the Kotlin glue *before* forwarding, or expose
  `checkPermissions`/`requestPermissions` as usual.
- **iOS.** Most frameworks prompt on first use. Add the `NS…UsageDescription` keys to the app's
  `Info.plist`, and document them in your README.
- **Web.** Some APIs need a user gesture, e.g. `DeviceMotionEvent.requestPermission()` on iOS Safari.
  Expose a dedicated method and call it from a click handler.

## Testing

| Layer | How |
|---|---|
| Shared logic | `commonTest` with `kotlin.test`. `npm run kmp:test` runs it on JVM, iOS simulator and Node. |
| Module + bridge | `KmpBridge(MyModule(fakes…), StandardTestDispatcher(testScheduler))` gives virtual time for streams (see `ConsumerModuleTest`). |
| Swift glue | XCTest in `ios/Tests`. `xcodebuild test -scheme <Package> -destination 'platform=iOS Simulator,name=…'` |
| Android glue | `cd android && ./gradlew test` (part of `npm run verify:android`) |
| End to end | The example app on an emulator, simulator and browser |

## Publishing

1. Bump `version` in `package.json`. It is also used as the Maven version of your artifacts.
2. Run `npm run build` (`prepublishOnly` does this), so `kmp-repo/`, `ios/Frameworks/` and
   `dist/esm/generated/` are fresh.
3. Run `npm publish`. Check with `npm pack --dry-run` that the generated folders are included.

Declare the base as a `peerDependency`, and tell app developers to install both packages.

## Alternative: one umbrella framework per app

### Why

Kotlin/Native has no shared runtime. Every separately linked iOS framework embeds its own copy of:

- the Kotlin runtime and garbage collector
- the stdlib
- kotlinx-coroutines and kotlinx-serialization
- `core`

These are the measured sizes (arm64 device slice, Release, Kotlin 2.4.21):

| Framework | Stripped (≈ install size) | Compressed (≈ download size) |
|---|---|---|
| `CapacitorKmpCore`: baseline, almost only shared runtime | 2.7 MB | 0.8 MB |
| `KmpConsumerShared`: baseline + device info + motion features | 3.2 MB | 0.96 MB |
| `KmpUmbrella`: baseline + device info + motion, the same features in **one** framework | 3.2 MB | 0.95 MB |

A feature costs only tens to hundreds of KB. The ~2.7 MB baseline is paid **once per framework**. In the
multi-plugin approach, an app with the base and N KMP plugins embeds N + 1 frameworks:

- The consumer example app embeds 5.9 MB of Kotlin frameworks. The umbrella example app embeds 3.2 MB
  for the same features.
- With the base and three plugins it would be ≈ 11 MB of mostly duplicated code, versus ≈ 3–3.5 MB for
  one umbrella framework.

Each framework also has its own runtime at execution time: its own heap, GC and `Dispatchers.Default`
thread pool. And Kotlin objects can't be passed between frameworks.

**No build setting removes this duplication.** Static frameworks, Swift export and stripping don't
share a runtime between separately compiled Kotlin frameworks. JetBrains' recommendation is to compile
all Kotlin that ships in an iOS app into **one** framework. In this setup that means **one KMP build
producing one plugin package**, which can still register *several* Capacitor plugins.

> Android has no such problem: Gradle resolves `core`, the stdlib and kotlinx once for the whole app.
> On the Web, each plugin's lazy Kotlin/JS chunk duplicates the stdlib (~140 KB gzipped). The umbrella
> approach fixes that too, with one JS module.

### How: one KMP build, several feature modules, one umbrella module

> **Reference implementation:**
> [`capacitor-kotlin-multi-platform-umbrella`](../../capacitor-kotlin-multi-platform-umbrella). It has the
> same device-info and motion features as the consumer plugin, as two Capacitor plugins (`KmpDevice`,
> `KmpMotion`) backed by **one** framework.
>
> | Kotlin frameworks on iOS (arm64, stripped / compressed) | |
> |---|---|
> | Consumer approach: `CapacitorKmpCore` + `KmpConsumerShared` | 5.9 MB / 1.8 MB |
> | Umbrella approach: `KmpUmbrella` | **3.2 MB / 0.95 MB** |

The umbrella **builds on the base without forking it.** `capacitor-kotlin-multi-platform` is a
*build-time* dependency (`devDependency`) that provides the convention plugin, the prebuilt `core`
library and the shared versions, exactly as for a consumer plugin. The differences:

- Each feature is a Gradle **module** of one KMP build, not a separate plugin.
- One **umbrella** module links `core` and every feature into the only XCFramework and JS module.
- The package registers **one Capacitor plugin per feature**.
- The published package is self-contained, and apps install **only** the umbrella. Installing the base
  plugin in the app would link its own `CapacitorKmpCore` framework, a second Kotlin runtime.

```text
kmp/
├── settings.gradle.kts       same as a consumer plugin (base build-logic, kmp-repo, version catalog)
├── features/device/          feature = KMP library: KmpModule + expect/actual, no framework of its own
├── features/motion/          may depend on other features: api(project(":features:device"))
└── umbrella/                 core + all features → ONE framework (KmpUmbrella) + ONE JS module
```

```kotlin
// kmp/settings.gradle.kts (plus pluginManagement / repositories / versionCatalogs as in step 2)
include(":features:device", ":features:motion", ":umbrella")

// kmp/features/device/build.gradle.kts – repeat per feature
plugins { id("io.github.capacitorkmp.plugin") }                       // library + api(core)
group = "io.github.capacitorkmp.umbrella"
capacitorKmp { android(namespace = "io.github.capacitorkmp.umbrella.device") }   // no iosFramework()

// kmp/umbrella/build.gradle.kts
plugins { id("io.github.capacitorkmp.plugin") }
group = "io.github.capacitorkmp.umbrella"
kotlin {
    sourceSets.commonMain.dependencies { api(project(":features:device")); api(project(":features:motion")) }
}
capacitorKmp {
    android(namespace = "io.github.capacitorkmp.umbrella.all")
    iosFramework("KmpUmbrella") {                // the ONLY framework: one Kotlin/Native runtime
        export(project(":features:device")); export(project(":features:motion"))   // core is exported automatically
    }
    jsModule("kmp-umbrella")
}

// kmp/umbrella/src/commonMain/kotlin/…/Umbrella.kt – an umbrella module needs at least one source file
internal object Umbrella

// kmp/umbrella/src/jsMain/kotlin/…/Bridges.kt – one factory per Capacitor plugin, one JS module
@JsExport fun createKmpDeviceBridge(): JsKmpBridge = DeviceModule().toJsBridge()
@JsExport fun createKmpMotionBridge(): JsKmpBridge = MotionModule().toJsBridge()
```

`capacitorExport` publishes every module to `kmp-repo/`. Only the module that declares `iosFramework(…)`
exports the XCFramework and JS module.

A small script, run after `capacitorExport` as part of `npm run kmp:build`, makes the package
self-contained: [`scripts/sync-from-base.mjs`](../../capacitor-kotlin-multi-platform-umbrella/scripts/sync-from-base.mjs).
It copies three things from the base package:

- the `core` Maven artifacts, into the umbrella's `kmp-repo/` (Android resolves them transitively)
- `KmpCapacitor.swift`, into the plugin's Swift target
- `kmp-web.ts`, into `src/generated/`

The plugin glue then registers one Capacitor plugin per feature:

```groovy
// android/build.gradle – one dependency for everything; the repo serves both groups
content { includeGroup "io.github.capacitorkmp"; includeGroup "io.github.capacitorkmp.umbrella" }
implementation "io.github.capacitorkmp.umbrella:umbrella-android:$umbrellaKmpVersion"
```

```kotlin
// android/src/main/kotlin/…/KmpMotionPlugin.kt – one plugin class per file
@CapacitorPlugin(name = "KmpMotion")
class KmpMotionPlugin : KmpCapacitorPlugin() {
    override fun createModule(): KmpModule = MotionModule()
    @PluginMethod fun startMotionUpdates(call: PluginCall) = forward(call)
    // …
}
```

```swift
// ios/Sources/KmpUmbrellaPlugin/KmpMotionPlugin.swift – one plugin class per file
import Capacitor
import KmpUmbrella   // core + all features

@objc(KmpMotionPlugin)
public class KmpMotionPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "KmpMotionPlugin"
    public let jsName = "KmpMotion"
    public let pluginMethods: [CAPPluginMethod] = [CAPPluginMethod(name: "startMotionUpdates", returnType: CAPPluginReturnPromise)]
    private lazy var kmp = KmpBridge(module: MotionModule())
    override public func load() { KmpCapacitor.connectEvents(of: self) { kmp.setEventListener(listener: $0) } }
    @objc func startMotionUpdates(_ call: CAPPluginCall) {
        KmpCapacitor.forward(call) { kmp.call(method: $0, argsJson: $1, onSuccess: $2, onError: $3) }
    }
}
```

```ts
// src/web.ts – every plugin loads the same Kotlin/JS module (evaluated once)
const umbrella = () => import('./generated/kmp/kmp-umbrella.mjs');
export class KmpMotionWeb extends KmpWebPlugin implements KmpMotionPlugin {
  constructor() { super(() => umbrella().then((m) => m.createKmpMotionBridge())); }
  // …
}
// src/index.ts – one memoised registerPlugin per feature
let motionWeb: Promise<KmpMotionWeb> | undefined;
export const KmpMotion = registerPlugin<KmpMotionPlugin>('KmpMotion', {
  web: () => (motionWeb ??= import('./web').then((m) => new m.KmpMotionWeb())),
});
```

Notes:

- **One plugin class per source file.** The Capacitor CLI registers only the *first* `@CapacitorPlugin`
  class (Android) or Objective-C-named class (iOS) it finds in each file. The iOS regex also matches the
  `@objc(…)` attribute inside **comments**: a comment mentioning it above the class registers a bogus
  plugin and hides the real one.
- **Features can depend on each other** (`api(project(":features:x"))`) and call each other's Kotlin
  directly, because they share one runtime. In the reference, `KmpMotion.isMotionAvailable()` asks the
  device feature whether it runs on an emulator or simulator, and recommends the simulated source there.
- **Alternative: fork the base.** If you would rather own everything in one repository, copy
  `capacitor-kotlin-multi-platform` and add the feature and umbrella modules to its `kmp/` build. Remove
  `iosFramework()`/`jsModule()` from `core`, and name the umbrella framework `CapacitorKmpCore` to keep
  `Package.swift`/the podspec unchanged. You trade easy base upgrades for a single repository.

### Benefits and trade-offs

| | Multi-plugin (steps 1–8) | Umbrella (extend the base) |
|---|---|---|
| Kotlin runtimes on iOS | One per plugin, plus the base's own | **One** |
| iOS size for N features | ≈ (N + 1) × 2.7 MB + feature code | **≈ 2.7 MB + feature code** |
| Web | Kotlin/JS stdlib duplicated per plugin chunk | **One JS module** |
| Sharing Kotlin objects between features | Not possible (JSON only) | **Direct calls between modules** |
| Packages an app installs | Base + every plugin | **One** |
| Kotlin version alignment | Every plugin must match the base | Single build, nothing to align |
| Modularity and ownership | **Independent repos, versions and releases per plugin** | One repo and release train. A change to any feature means rebuilding and releasing the umbrella |
| Unused features | **Apps install only what they use** | Every app gets every feature, unless you build an app-specific umbrella. Frameworks you link (CoreMotion, CoreLocation, …) may also require `NS…UsageDescription` keys in every app |
| Third-party KMP plugins | **Can be mixed in freely** (each brings its own runtime) | Must be added as source/Maven modules to your umbrella build; a separately compiled KMP plugin still adds its own runtime |
| Build time | Small, independent builds | One larger build (Gradle caching keeps incremental builds fast) |

**Rule of thumb.**

- **Use the umbrella** when one team owns several KMP features that ship together in the same app(s),
  or when iOS binary size matters.
- **Use separate plugins** for features you want to publish and version independently, especially for
  other teams or the public.
- **Mix both:** build features as KMP *library* modules (`io.github.capacitorkmp.library`, published to a
  Maven repository). Each app can then assemble its own umbrella from the features it needs.

## Customising the base plugin

You can also change `capacitor-kotlin-multi-platform` itself, e.g. fork it for your organisation.

- **Add shared helpers for all plugins.** Put them in `kmp/core/src/commonMain`, e.g. a common
  `KmpLogger`, retry helpers or a shared error vocabulary. Platform helpers go in
  `androidMain`/`iosMain`/`jsMain`. Bump the version, run `npm run build`, then rebuild consumers.
- **Change Kotlin or library versions.** Edit `kmp/gradle/libs.versions.toml`. Keep `kotlinCompat` and
  `kotlinStdlibCompat` readable by the Kotlin version used by Capacitor apps (2.2.x for Capacitor 8), and
  check that kotlinx releases don't depend on a newer stdlib:
  `curl …/kotlinx-coroutines-core-jvm/<v>/*.pom | grep -A1 kotlin-stdlib`.
- **Change target or export behaviour.** Edit the convention plugin in
  `kmp/build-logic/src/main/kotlin/io/github/capacitorkmp/gradle/`. For example:
  - add `iosX64()` for Intel simulators, and remove the `EXCLUDED_ARCHS` lines from the podspecs
  - switch the XCFramework to static
  - add `wasmJs`
- **Change the Swift or TS glue.** Edit `ios/Sources/CapacitorKmpSupport/KmpCapacitor.swift` and
  `src/kmp-web.ts`.
- **Rename the Maven group or namespace.** Change `CORE_GROUP` in `kmp/build-logic/build.gradle.kts`,
  `group` and `android(namespace=…)` in `kmp/core/build.gradle.kts`, the
  `includeGroup`/dependency in `android/build.gradle`, and the Kotlin packages.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.x` in the app | A dependency of your shared code brings in a newer stdlib. Pin it to a version built for Kotlin ≤ 2.3, or align `kotlinStdlibCompat` with the app's Kotlin. |
| `Could not find com.example…:shared-android` in the app | Run `npm run kmp:build` in the plugin (is `kmp-repo/` populated?) and `npx cap sync`. If the app uses `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, add the `kmp-repo` folders to `settings.gradle`. |
| iOS: `No such module 'MyPluginShared'` / missing xcframework | Run `npm run kmp:build`. In Xcode, File › Packages › Reset Package Caches. |
| iOS: `Unable to find matching slice … x86_64` | You are building for an Intel simulator; only arm64 simulators are supported (see above). |
| Web: events never arrive | Memoise the web factory in `index.ts` (see step 7). |
| Web: `Failed to fetch dynamically imported module …/generated/kmp/…` | `dist/esm/generated` is missing. Run `npm run build` (it copies the Kotlin/JS output). |
| `Cannot use 'Nothing' as reified type parameter` | A handler that only throws needs an explicit type: `method<Unit>("x") { throw … }`. |
| First Gradle build is slow | Kotlin/Native downloads its toolchain (~1 GB) once to `~/.konan`. |
