package io.github.capacitorkmp.gradle

import com.android.build.api.dsl.androidLibrary
import groovy.json.JsonSlurper
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

/**
 * `io.github.capacitorkmp.library`
 *
 * Turns a Gradle module into a Kotlin Multiplatform library whose outputs can be consumed by a
 * Capacitor plugin on every platform:
 *
 * | Platform | Target(s)                         | Exported to (by `capacitorExport`)             |
 * |----------|-----------------------------------|------------------------------------------------|
 * | Android  | `androidLibrary` (AAR)            | `<plugin>/kmp-repo` (local Maven repository)   |
 * | iOS      | `iosArm64`, `iosSimulatorArm64`   | `<plugin>/ios/Frameworks/<Name>.xcframework`   |
 * | Web      | `js` (IR, ES modules, `.d.mts`)   | `<plugin>/src/generated/kmp/`                  |
 *
 * The module must call `capacitorKmp { android(...); iosFramework(...) }` – see [CapacitorKmpExtension].
 */
open class CapacitorKmpLibraryPlugin : Plugin<Project> {

    /** Whether the base `core` library is added as an `api` dependency (and exported to iOS). */
    protected open val dependsOnCore: Boolean = false

    override fun apply(project: Project): Unit = with(project) {
        // We declare the stdlib ourselves (see configureDependencies) so Android gets a compatible one.
        extensions.extraProperties["kotlin.stdlib.default.dependency"] = "false"
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        pluginManager.apply("maven-publish")

        val pluginRoot = rootProject.layout.projectDirectory.dir("..")
        if (version == Project.DEFAULT_VERSION) version = readPackageVersion(pluginRoot.file("package.json").asFile)

        val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
        val capacitorKmp = extensions.create<CapacitorKmpExtension>("capacitorKmp", project, kotlin, dependsOnCore)
        capacitorKmp.pluginDir.convention(pluginRoot)

        configureKotlin(kotlin)
        configureDependencies(kotlin)
        configurePublishing(capacitorKmp)
        configureExportTask(capacitorKmp)
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    private fun Project.configureKotlin(kotlin: KotlinMultiplatformExtension) = with(kotlin) {
        // Android artifacts are compiled into Capacitor apps whose own Kotlin compiler (2.2.x for
        // Capacitor 8) can only read metadata up to one minor version newer. Pin the language/API
        // level and the stdlib that our POMs advertise accordingly.
        val compat = KotlinVersion.fromVersion(BaseBuildInfo.KOTLIN_COMPAT)
        compilerOptions {
            languageVersion.set(compat)
            apiVersion.set(compat)
            freeCompilerArgs.addAll("-Xexpect-actual-classes", "-Xsuppress-version-warnings")
        }

        androidLibrary {
            compileSdk = BaseBuildInfo.ANDROID_COMPILE_SDK.toInt()
            minSdk = BaseBuildInfo.ANDROID_MIN_SDK.toInt()
            withHostTest { }
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }

        iosArm64()
        iosSimulatorArm64()
        // Kotlin/Native interop with Apple frameworks (also for the shared iosMain metadata compilation).
        sourceSets.matching { it.name.startsWith("ios") }.configureEach {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }

        js {
            nodejs {
                testTask { useMocha { timeout = "10s" } }
            }
            // An "executable" without a main() gives us dead-code elimination; every @JsExport
            // declaration is kept and becomes the module's public API.
            binaries.executable()
            useEsModules()
            generateTypeScriptDefinitions()
            compilerOptions.optIn.add("kotlin.js.ExperimentalJsExport")
        }

        applyDefaultHierarchyTemplate()
        Unit
    }

    private fun Project.configureDependencies(kotlin: KotlinMultiplatformExtension) {
        // Android (and the common metadata) is compiled against an older stdlib so that the published
        // AAR/POM never drags a newer kotlin-stdlib into Capacitor apps. Kotlin/JS must use the stdlib
        // matching the compiler; Kotlin/Native always uses the stdlib bundled with the compiler.
        kotlin.sourceSets.getByName("commonMain").dependencies {
            implementation("org.jetbrains.kotlin:kotlin-stdlib:${BaseBuildInfo.KOTLIN_STDLIB_COMPAT}")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${BaseBuildInfo.COROUTINES_VERSION}")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:${BaseBuildInfo.SERIALIZATION_VERSION}")
            if (dependsOnCore) api(CapacitorKmpExtension.CORE_COORDINATES)
        }
        kotlin.sourceSets.getByName("jsMain").dependencies {
            implementation("org.jetbrains.kotlin:kotlin-stdlib:${BaseBuildInfo.KOTLIN_VERSION}")
        }
        kotlin.sourceSets.getByName("commonTest").dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${BaseBuildInfo.COROUTINES_VERSION}")
        }
    }

    private fun Project.configurePublishing(ext: CapacitorKmpExtension) {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "capacitorKmp"
                    url = uri(ext.pluginDir.dir("kmp-repo"))
                }
            }
        }
    }

    private fun Project.configureExportTask(ext: CapacitorKmpExtension) {
        val exportIos = tasks.register<Sync>("capacitorExportIos") {
            group = TASK_GROUP
            description = "Copies the release XCFramework into <plugin>/ios/Frameworks."
            val name = ext.frameworkName
            dependsOn(name.map { "assemble${it}ReleaseXCFramework" })
            from(name.map { layout.buildDirectory.dir("XCFrameworks/release/$it.xcframework") })
            into(ext.pluginDir.zip(name) { dir, n -> dir.dir("ios/Frameworks/$n.xcframework") })
        }
        val exportJs = tasks.register<Sync>("capacitorExportJs") {
            group = TASK_GROUP
            description = "Copies the Kotlin/JS ES modules (+ .d.mts) into <plugin>/src/generated/kmp."
            dependsOn("jsProductionExecutableCompileSync")
            from(layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin")) {
                include("*.mjs", "*.d.mts")
            }
            into(ext.pluginDir.dir("src/generated/kmp"))
        }
        val cleanRepo = tasks.register<Delete>("capacitorCleanRepo") {
            group = TASK_GROUP
            description = "Removes previously exported Maven artifacts of this module."
            val artifact = project.name
            val groupDir = ext.pluginDir.map { it.dir("kmp-repo/" + project.group.toString().replace('.', '/')).asFile }
            // <group>/<name>, <group>/<name>-android, <group>/<name>-iosarm64, ...
            delete(groupDir.map { dir -> dir.listFiles { f -> f.name == artifact || f.name.startsWith("$artifact-") }.orEmpty().toList() })
        }
        tasks.matching { it.name == "publishAllPublicationsToCapacitorKmpRepository" }.configureEach { dependsOn(cleanRepo) }
        tasks.register("capacitorExport") {
            group = TASK_GROUP
            description = "Builds and exports Android (Maven), iOS (XCFramework) and Web (ES module) artifacts."
            // Every module publishes its Maven artifacts (Android resolves them transitively). Only the
            // *entry* module – the one declaring `iosFramework(...)` – exports the XCFramework and the JS
            // module, so feature modules of an umbrella build don't produce frameworks of their own.
            dependsOn("publishAllPublicationsToCapacitorKmpRepository")
            dependsOn(ext.frameworkName.map { listOf(exportIos, exportJs) }.orElse(emptyList()))
        }
    }

    private fun readPackageVersion(packageJson: java.io.File): String {
        if (!packageJson.exists()) return "0.0.0-SNAPSHOT"
        @Suppress("UNCHECKED_CAST")
        return ((JsonSlurper().parse(packageJson) as Map<String, Any?>)["version"] as? String) ?: "0.0.0-SNAPSHOT"
    }

    companion object {
        const val TASK_GROUP = "capacitor kmp"
    }
}

/**
 * `io.github.capacitorkmp.plugin`
 *
 * Everything [CapacitorKmpLibraryPlugin] does, plus an `api` dependency on the base `core` library
 * (`KmpModule`, `KmpBridge`, Capacitor adapters…) which is also exported to the iOS framework so that
 * Swift code can use it.
 */
class CapacitorKmpPlugin : CapacitorKmpLibraryPlugin() {
    override val dependsOnCore: Boolean = true
}
