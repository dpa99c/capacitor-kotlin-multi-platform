package io.github.capacitorkmp.core

import platform.UIKit.UIDevice

actual object KmpPlatform {
    actual val name: String = "ios"
    actual val osVersion: String get() = UIDevice.currentDevice.systemVersion
}
