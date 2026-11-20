package io.github.capacitorkmp.core

/**
 * Minimal information about the platform the shared code is running on – an `expect`/`actual`
 * example implemented with Android `Build`, iOS `UIDevice` and the browser `navigator`.
 */
expect object KmpPlatform {
    /** `"android"`, `"ios"` or `"web"` – matches `Capacitor.getPlatform()`. */
    val name: String

    /** Operating system (Android/iOS) or browser user agent (Web) version string. */
    val osVersion: String
}
