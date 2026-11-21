package io.github.capacitorkmp.gradle

import com.android.build.api.dsl.androidLibrary
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import javax.inject.Inject

/**
 * Configuration for a Capacitor KMP module:
 *
 * ```kotlin
 * capacitorKmp {
 *     android(namespace = "com.example.myplugin.shared")
 *     iosFramework("MyPluginShared")
 *     jsModule("my-plugin-shared")          // optional, defaults to the Gradle project name
 * }
 * ```
 */
abstract class CapacitorKmpExtension @Inject constructor(
    private val project: Project,
    private val kotlin: KotlinMultiplatformExtension,
    private val dependsOnCore: Boolean,
) {
    /** Root directory of the Capacitor plugin (where `package.json` lives). Defaults to `<kmp root>/..`. */
    abstract val pluginDir: DirectoryProperty

    /** Name of the iOS XCFramework (and Swift module). Set via [iosFramework]. */
    abstract val frameworkName: Property<String>

    /**
     * Configures the Android target. [namespace] is the Android namespace of the generated AAR and,
     * unless the project sets `group` itself, the Maven group of the exported artifacts.
     */
    fun android(namespace: String) {
        kotlin.androidLibrary { this.namespace = namespace }
        if (project.group.toString().isEmpty() || project.group == project.rootProject.name) project.group = namespace
    }

    /**
     * Declares the dynamic XCFramework that the plugin's `Package.swift` / podspec vendor, and makes
     * this module the plugin's *entry* module: `capacitorExport` exports its XCFramework and JS module.
     * In an umbrella build, call this only in the umbrella module and `export(project(":feature"))`
     * every feature module whose API Swift needs.
     * The base `core` library is exported automatically for consumer plugins so Swift can use
     * `KmpBridge` & co. Use [configure] to export further modules or set binary options.
     */
    fun iosFramework(name: String, configure: Framework.() -> Unit = {}) {
        frameworkName.set(name)
        frameworkName.disallowChanges()
        val xcf = project.XCFramework(name)
        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
            binaries.framework {
                baseName = name
                isStatic = false
                binaryOption("bundleId", "${project.group}.$name")
                if (dependsOnCore) export(CORE_COORDINATES)
                configure()
                xcf.add(this)
            }
        }
    }

    /** Overrides the file name of the generated Kotlin/JS ES module (`<name>.mjs`). */
    fun jsModule(name: String) {
        kotlin.targets.withType(KotlinJsIrTarget::class.java).configureEach { outputModuleName.set(name) }
    }

    companion object {
        /** Maven coordinates of the base `core` library that matches this build-logic. */
        const val CORE_COORDINATES: String = "${BaseBuildInfo.CORE_GROUP}:core:${BaseBuildInfo.CORE_VERSION}"
    }
}
