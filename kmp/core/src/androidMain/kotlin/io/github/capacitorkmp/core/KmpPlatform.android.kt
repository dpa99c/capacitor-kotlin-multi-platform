package io.github.capacitorkmp.core

import android.os.Build

actual object KmpPlatform {
    actual val name: String = "android"
    actual val osVersion: String get() = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
