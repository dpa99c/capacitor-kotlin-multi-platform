package io.github.capacitorkmp.core

import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * The base plugin's own Capacitor plugin (`CapacitorKmp` in JavaScript).
 *
 * All logic lives in the shared Kotlin [CoreInfoModule] (kmp/core/src/commonMain); this class only
 * declares the plugin methods and forwards them – the same pattern every KMP-backed plugin uses.
 */
@CapacitorPlugin(name = "CapacitorKmp")
class CapacitorKmpPlugin : KmpCapacitorPlugin() {

    override fun createModule(): KmpModule = CoreInfoModule()

    @PluginMethod
    fun getKmpInfo(call: PluginCall) = forward(call)

    @PluginMethod
    fun echo(call: PluginCall) = forward(call)
}
