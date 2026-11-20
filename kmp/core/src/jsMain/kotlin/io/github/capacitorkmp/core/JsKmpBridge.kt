package io.github.capacitorkmp.core

import kotlin.js.Promise

/**
 * JavaScript-facing wrapper of a [KmpBridge], consumed by the `KmpWebPlugin` TypeScript helper.
 *
 * Plugins export a factory for their module, e.g.
 * ```kotlin
 * @JsExport fun createMyPluginBridge(): JsKmpBridge = MyModule().toJsBridge()
 * ```
 */
@JsExport
class JsKmpBridge internal constructor(private val bridge: KmpBridge) {

    /** Names of the methods implemented by the module. */
    val methodNames: Array<String> get() = bridge.methodNames.toTypedArray()

    /**
     * Invokes [method] with JSON options. Resolves with the JSON result object; rejects with an
     * `Error` carrying a `code` property (see [KmpErrorCode]).
     */
    fun call(method: String, argsJson: String): Promise<String> = Promise { resolve, reject ->
        bridge.call(
            method,
            argsJson,
            { resolve(it) },
            { code, message -> reject(jsError(code, message)) },
        )
    }

    /** Sets the listener receiving `(eventName, payloadJson)`; pass `null` to remove it. */
    fun setEventListener(listener: ((event: String, payloadJson: String) -> Unit)?) {
        bridge.setEventListener(listener)
    }

    fun close() {
        bridge.close()
    }
}

/** Hosts this module in a [KmpBridge] and wraps it for JavaScript. */
fun KmpModule.toJsBridge(): JsKmpBridge = JsKmpBridge(KmpBridge(this))

/** Factory used by the base plugin's web implementation. */
@JsExport
fun createCoreInfoBridge(): JsKmpBridge = CoreInfoModule().toJsBridge()

private fun jsError(code: String, message: String): Throwable {
    val error: dynamic = js("new Error(message)")
    error.code = code
    return error.unsafeCast<Throwable>()
}
