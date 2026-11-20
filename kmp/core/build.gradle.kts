import io.github.capacitorkmp.gradle.BaseBuildInfo

plugins {
    id("io.github.capacitorkmp.library")
}

// Maven coordinates: io.github.capacitorkmp:core:<package.json version>
group = BaseBuildInfo.CORE_GROUP

// Expose the versions this core was built with to Kotlin code (see CoreInfo).
val generateCoreInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/coreinfo")
    val coreVersion = project.version.toString()
    inputs.property("coreVersion", coreVersion)
    outputs.dir(outDir)
    doLast {
        outDir.get().file("io/github/capacitorkmp/core/CoreBuildInfo.kt").asFile.apply { parentFile.mkdirs() }.writeText(
            """
            |package io.github.capacitorkmp.core
            |
            |/** Generated at build time. Do not edit. */
            |internal object CoreBuildInfo {
            |    const val CORE_VERSION: String = "$coreVersion"
            |    const val KOTLIN_COMPILER_VERSION: String = "${BaseBuildInfo.KOTLIN_VERSION}"
            |}
            |""".trimMargin(),
        )
    }
}

capacitorKmp {
    android(namespace = "io.github.capacitorkmp.core")
    iosFramework("CapacitorKmpCore")
    jsModule("capacitor-kmp-core")
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            // Capacitor's Android runtime is provided by the app; only compile against it.
            compileOnly(libs.capacitor.android)
        }
        commonMain {
            kotlin.srcDir(generateCoreInfo)
        }
    }
}

