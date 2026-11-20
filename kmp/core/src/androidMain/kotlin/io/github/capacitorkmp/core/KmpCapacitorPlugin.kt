package io.github.capacitorkmp.core

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall

/**
 * Base class for Android Capacitor plugins implemented by a shared [KmpModule].
 *
 * ```kotlin
 * @CapacitorPlugin(name = "MyPlugin")
 * class MyPlugin : KmpCapacitorPlugin() {
 *     override fun createModule() = MyModule()
 *
 *     @PluginMethod fun greet(call: PluginCall) = forward(call)
 * }
 * ```
 *
 * Every `@PluginMethod` simply [forward]s the call to the module; events emitted by the module are
 * delivered to JavaScript listeners via `notifyListeners`.
 */
abstract class KmpCapacitorPlugin : Plugin() {
    /** The bridge hosting the module returned by [createModule]. Available after [load]. */
    protected lateinit var kmp: KmpBridge
        private set

    /** Creates the shared module backing this plugin. Called once, from [load]. */
    protected abstract fun createModule(): KmpModule

    override fun load() {
        super.load()
        KmpAndroid.init(context)
        kmp = KmpBridge(createModule())
        kmp.setEventListener { event, payloadJson -> notifyListeners(event, JSObject(payloadJson)) }
    }

    /** Forwards [call] to the shared module method of the same name (or [method]). */
    protected fun forward(call: PluginCall, method: String = call.methodName) {
        kmp.handle(call, method)
    }

    override fun handleOnDestroy() {
        if (::kmp.isInitialized) kmp.close()
        super.handleOnDestroy()
    }
}

/**
 * Forwards a Capacitor [PluginCall] to this bridge: the call's options are passed as JSON, the result
 * resolves the call and a [KmpException] rejects it with its code and message.
 */
fun KmpBridge.handle(call: PluginCall, method: String = call.methodName) {
    call(
        method,
        call.data.toString(),
        { resultJson -> call.resolve(JSObject(resultJson)) },
        { code, message -> call.reject(message, code) },
    )
}
