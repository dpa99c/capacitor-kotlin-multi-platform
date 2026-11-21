# capacitor-kotlin-multi-platform

Write the logic of a Capacitor plugin **once, in Kotlin**, and run it natively on **Android** (JVM),
**iOS** (Kotlin/Native) and in the **browser** (Kotlin/JS).

This package is a base framework for building Kotlin Multiplatform (KMP) Capacitor plugins:

| It provides | What it does |
|---|---|
| **`core` Kotlin library** (`io.github.capacitorkmp:core`) | `KmpModule` (declare plugin methods & emit events in shared Kotlin), `KmpBridge` (JSON boundary between Capacitor and Kotlin), `KmpException`, `KmpPlatform`, Android `KmpCapacitorPlugin` base class, Kotlin/JS `JsKmpBridge`. |
| **Gradle convention plugin** (`io.github.capacitorkmp.plugin`) | Configures the KMP targets (Android, iOS arm64 + simulator, JS), Kotlin/Android compatibility settings, the iOS XCFramework, and `capacitorExport` which drops the AAR, XCFramework and JS module into your plugin. |
| **Swift helper** (`CapacitorKmpSupport`) | `KmpCapacitor.forward(call)` / `connectEvents(of:)` – one-line iOS glue. |
| **TypeScript helper** (`KmpWebPlugin`) | Base class for the web implementation – forwards calls/events to the Kotlin/JS module. |
| **Shared versions** (`kmp/gradle/libs.versions.toml`) | Kotlin 2.4.21, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.9.0, AGP 8.13.0 – one source of truth for every plugin built on the base. |

The base plugin is itself a (tiny) KMP plugin – `CapacitorKmp.getKmpInfo()` / `echo()` – which acts as
an end-to-end self-test of the pipeline on every platform.

👉 For a complete example (device info + accelerometer with shared step counting and shake detection) see
[`capacitor-kotlin-multi-platform-consumer`](https://github.com/dpa99c/capacitor-kotlin-multi-platform-consumer).

- **[docs/EXTENDING.md](docs/EXTENDING.md)** – build your own KMP-powered plugin, step by step
  - including the **[umbrella approach](docs/EXTENDING.md#alternative-one-umbrella-framework-per-app)**:
    put several features in one plugin, so iOS ships one Kotlin runtime instead of one per plugin
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** – how it works, compatibility notes, limitations

## How it works

```
                 ┌───────────────────── shared Kotlin (commonMain) ─────────────────────┐
                 │  class MyModule : KmpModule() { init { method("greet") { … } } }      │
                 │  expect fun platformThing(): Thing   ← actual per platform            │
                 └────────────────────────────────────────────────────────────────────────┘
                     │ AAR (kmp-repo/)          │ XCFramework (ios/Frameworks/)   │ ES module (src/generated/kmp/)
   Android ──► MyPlugin : KmpCapacitorPlugin    iOS ──► CAPPlugin + KmpCapacitor  Web ──► KmpWebPlugin
               @PluginMethod fun greet(call)            .forward(call)                   this.invoke('greet', options)
                   = forward(call)
```

Every call crosses the same JSON boundary (`KmpBridge.call(method, argsJson)`), so the per-platform
glue is one line per method and all behaviour – validation, business rules, errors, events – lives in
Kotlin.

## Requirements

| | |
|---|---|
| Capacitor | 8.x |
| Android | minSdk 24, compileSdk 36, app Kotlin 2.2+ (Capacitor 8 default) |
| iOS | 15.0+, Swift Package Manager (default) or CocoaPods; Apple-silicon simulators (no x86_64 slice) |
| Web | Any modern browser (ES2017 + ES modules) |
| Building the Kotlin code | JDK 17+ (21 recommended), Xcode with iOS SDK, Node 20+. Gradle and Kotlin are fetched by the Gradle wrapper. |

## Install

```bash
npm install capacitor-kotlin-multi-platform
npx cap sync
```

The npm package ships prebuilt Kotlin artifacts (`kmp-repo/`, `ios/Frameworks/`, `dist/esm/generated/`),
so apps don't need a Kotlin toolchain. Android apps need no manual Gradle changes – the plugin registers
its bundled Maven repository automatically.

## Usage

```ts
import { CapacitorKmp } from 'capacitor-kotlin-multi-platform';

const info = await CapacitorKmp.getKmpInfo();
// { platform: 'ios', osVersion: '18.2', coreVersion: '0.1.0', kotlinVersion: '2.4.21', … }
```

## Developing this plugin

```bash
npm install
npm run kmp:test      # Kotlin tests on Android (host), iOS simulator and JS (Node)
npm run build         # kmp:build (Gradle capacitorExport) + TypeScript/rollup
npm run verify        # iOS (xcodebuild), Android (Gradle) and web builds

cd example-app && npm install && npm run build && npx cap sync
npx cap run android   # or: npx cap run ios / npm start (web)
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## API

<docgen-index>

* [`getKmpInfo()`](#getkmpinfo)
* [`echo(...)`](#echo)
* [Interfaces](#interfaces)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

The base plugin's own API. It is a self-test of the Kotlin Multiplatform pipeline: every method is
implemented once, in shared Kotlin (`kmp/core/src/commonMain/.../CoreInfoModule.kt`), and runs
natively on Android, iOS and (compiled to JavaScript) on the Web.

### getKmpInfo()

```typescript
getKmpInfo() => Promise<KmpInfo>
```

Returns information about the shared Kotlin runtime on the current platform.

**Returns:** <code>Promise&lt;<a href="#kmpinfo">KmpInfo</a>&gt;</code>

**Since:** 0.1.0

--------------------


### echo(...)

```typescript
echo(options: EchoOptions) => Promise<EchoResult>
```

Round-trips a value through the shared Kotlin code.

| Param         | Type                                                |
| ------------- | --------------------------------------------------- |
| **`options`** | <code><a href="#echooptions">EchoOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#echoresult">EchoResult</a>&gt;</code>

**Since:** 0.1.0

--------------------


### Interfaces


#### KmpInfo

| Prop                      | Type                                     | Description                                                                          |
| ------------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------ |
| **`platform`**            | <code>'android' \| 'ios' \| 'web'</code> | Platform reported by the Kotlin `expect`/`actual` `KmpPlatform` object.              |
| **`osVersion`**           | <code>string</code>                      | OS version (Android/iOS) or user agent (Web), read by platform-specific Kotlin code. |
| **`coreVersion`**         | <code>string</code>                      | Version of the capacitor-kotlin-multi-platform core library.                         |
| **`kotlinVersion`**       | <code>string</code>                      | Kotlin compiler version the shared code was built with.                              |
| **`kotlinStdlibVersion`** | <code>string</code>                      | Kotlin standard library version available at runtime.                                |


#### EchoResult

| Prop           | Type                                     | Description                                         |
| -------------- | ---------------------------------------- | --------------------------------------------------- |
| **`value`**    | <code>string</code>                      |                                                     |
| **`platform`** | <code>'android' \| 'ios' \| 'web'</code> | Platform on which the Kotlin code handled the call. |


#### EchoOptions

| Prop        | Type                |
| ----------- | ------------------- |
| **`value`** | <code>string</code> |

</docgen-api>
