import groovy.json.JsonSlurper

plugins {
    `kotlin-dsl`
}

group = "io.github.capacitorkmp"

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.android.gradle.plugin)
}

gradlePlugin {
    plugins {
        // Base KMP setup (targets, compiler options, XCFramework/JS/Maven export). Used by `core`.
        register("capacitorKmpLibrary") {
            id = "io.github.capacitorkmp.library"
            implementationClass = "io.github.capacitorkmp.gradle.CapacitorKmpLibraryPlugin"
        }
        // Library setup + `api(core)` + core exported to the iOS framework. Used by consumer plugins.
        register("capacitorKmpPlugin") {
            id = "io.github.capacitorkmp.plugin"
            implementationClass = "io.github.capacitorkmp.gradle.CapacitorKmpPlugin"
        }
    }
}

// The base plugin's npm version doubles as the Maven version of the `core` artifact. Bake it into
// the convention plugin so consumers always depend on the core that ships next to this build-logic.
val packageJson = layout.projectDirectory.file("../../package.json")
val catalogVersions = mapOf(
    "KOTLIN_VERSION" to libs.versions.kotlin.get(),
    "KOTLIN_COMPAT" to libs.versions.kotlinCompat.get(),
    "KOTLIN_STDLIB_COMPAT" to libs.versions.kotlinStdlibCompat.get(),
    "COROUTINES_VERSION" to libs.versions.coroutines.get(),
    "SERIALIZATION_VERSION" to libs.versions.serialization.get(),
    "CAPACITOR_ANDROID_VERSION" to libs.versions.capacitorAndroid.get(),
    "ANDROID_COMPILE_SDK" to libs.versions.androidCompileSdk.get(),
    "ANDROID_MIN_SDK" to libs.versions.androidMinSdk.get(),
)
val generateBuildInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/buildinfo")
    inputs.file(packageJson)
    inputs.property("catalogVersions", catalogVersions)
    outputs.dir(outDir)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val pkg = JsonSlurper().parse(packageJson.asFile) as Map<String, Any?>
        val file = outDir.get().file("io/github/capacitorkmp/gradle/BaseBuildInfo.kt").asFile
        file.parentFile.mkdirs()
        val constants = mapOf("CORE_GROUP" to "io.github.capacitorkmp", "CORE_VERSION" to "${pkg["version"]}") +
            catalogVersions
        file.writeText(
            buildString {
                appendLine("package io.github.capacitorkmp.gradle")
                appendLine()
                appendLine("/** Generated from capacitor-kotlin-multi-platform/package.json + libs.versions.toml. Do not edit. */")
                appendLine("object BaseBuildInfo {")
                constants.forEach { (k, v) -> appendLine("    const val $k: String = \"$v\"") }
                appendLine("}")
            },
        )
    }
}
sourceSets.main { kotlin.srcDir(generateBuildInfo) }
