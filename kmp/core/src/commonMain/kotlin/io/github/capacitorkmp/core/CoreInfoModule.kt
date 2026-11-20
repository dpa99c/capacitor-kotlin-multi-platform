package io.github.capacitorkmp.core

import kotlinx.serialization.Serializable

/**
 * The base plugin's own module (`CapacitorKmp` in JavaScript). It is a self-test that proves the KMP
 * pipeline works end-to-end on each platform.
 */
class CoreInfoModule : KmpModule() {
    init {
        method("getKmpInfo") {
            KmpInfo(
                platform = KmpPlatform.name,
                osVersion = KmpPlatform.osVersion,
                coreVersion = CoreBuildInfo.CORE_VERSION,
                kotlinVersion = CoreBuildInfo.KOTLIN_COMPILER_VERSION,
                kotlinStdlibVersion = KotlinVersion.CURRENT.toString(),
            )
        }
        method("echo") {
            val value = requireString("value")
            EchoResult(value = value, platform = KmpPlatform.name)
        }
    }
}

@Serializable
data class KmpInfo(
    /** Platform reported by the Kotlin `expect`/`actual` [KmpPlatform] – `android`, `ios` or `web`. */
    val platform: String,
    val osVersion: String,
    /** Version of the capacitor-kotlin-multi-platform core library. */
    val coreVersion: String,
    /** Kotlin compiler version the shared code was built with. */
    val kotlinVersion: String,
    /** Kotlin standard library version available at runtime. */
    val kotlinStdlibVersion: String,
)

@Serializable
data class EchoResult(val value: String, val platform: String)
