package io.github.capacitorkmp.core

actual object KmpPlatform {
    actual val name: String = "web"
    actual val osVersion: String
        get() = if (js("typeof navigator !== 'undefined'") as Boolean) {
            js("navigator.userAgent") as String
        } else {
            "unknown"
        }
}
