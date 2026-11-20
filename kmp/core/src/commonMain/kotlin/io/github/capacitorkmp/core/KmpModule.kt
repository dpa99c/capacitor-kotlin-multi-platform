package io.github.capacitorkmp.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * The shared (common) implementation of a Capacitor plugin.
 *
 * Register plugin methods in `init` with [method]; each handler receives a [KmpCall] and returns any
 * `@Serializable` value (or `Unit` for no data). Push events to JavaScript listeners with [emit].
 *
 * ```kotlin
 * class GreeterModule : KmpModule() {
 *     init {
 *         method("greet") { Greeting("Hello ${requireString("name")} from ${KmpPlatform.name}") }
 *     }
 * }
 * @Serializable data class Greeting(val message: String)
 * ```
 *
 * A module is hosted by a [KmpBridge], which the platform glue (Android `Plugin`, iOS `CAPPlugin`,
 * Web `WebPlugin`) forwards calls to.
 */
abstract class KmpModule {
    private val handlers = LinkedHashMap<String, suspend (KmpCall) -> JsonElement>()
    private var host: Host? = null

    /** Names of all registered methods. */
    val methodNames: List<String> get() = handlers.keys.toList()

    /**
     * Scope for long-running work (streams, timers…). It is cancelled when the hosting bridge is closed.
     * Only available once the module is attached to a [KmpBridge].
     */
    protected val scope: CoroutineScope
        get() = checkNotNull(host) { "${this::class.simpleName} is not attached to a KmpBridge" }.scope

    /** Registers the handler for the plugin method [name]. The return value is serialized to JSON. */
    protected inline fun <reified R> method(name: String, noinline handler: suspend KmpCall.() -> R) {
        val serializer = serializer<R>()
        registerHandler(name) { call -> KmpJson.encodeToJsonElement(serializer, call.handler()) }
    }

    /** Sends [payload] to the JavaScript listeners of [event] (`MyPlugin.addListener(event, …)`). */
    protected inline fun <reified T> emit(event: String, payload: T) {
        emitJson(event, KmpJson.encodeToJsonElement(serializer<T>(), payload))
    }

    /** Called once the module is attached to a bridge ([scope] is available). */
    protected open fun onAttach() {}

    /** Called when the hosting bridge is closed, before [scope] is cancelled. */
    protected open fun onClose() {}

    @PublishedApi
    internal fun registerHandler(name: String, handler: suspend (KmpCall) -> JsonElement) {
        require(name !in handlers) { "Method '$name' is already registered" }
        handlers[name] = handler
    }

    @PublishedApi
    internal fun emitJson(event: String, payload: JsonElement) {
        host?.emit?.invoke(event, payload)
    }

    internal suspend fun invoke(call: KmpCall): JsonElement {
        val handler = handlers[call.method]
            ?: throw KmpException(KmpErrorCode.UNIMPLEMENTED, "Method '${call.method}' is not implemented by ${this::class.simpleName}")
        return handler(call)
    }

    internal fun attach(scope: CoroutineScope, emit: (String, JsonElement) -> Unit) {
        check(host == null) { "${this::class.simpleName} is already attached to a KmpBridge" }
        host = Host(scope, emit)
        onAttach()
    }

    internal fun detach() {
        onClose()
        host = null
    }

    private class Host(val scope: CoroutineScope, val emit: (String, JsonElement) -> Unit)

    internal companion object {
        val EMPTY_RESULT = JsonObject(emptyMap())
    }
}
