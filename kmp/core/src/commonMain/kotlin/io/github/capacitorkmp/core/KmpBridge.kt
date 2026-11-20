package io.github.capacitorkmp.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Hosts a [KmpModule] and exposes it through a platform-neutral, JSON-string based API that the
 * Capacitor glue on Android (Kotlin), iOS (Swift) and Web (TypeScript) can call:
 *
 * - [call] — invoke a method with the JSON options object; completes via callbacks.
 * - [setEventListener] — receive events emitted by the module.
 * - [close] — cancel all running work.
 *
 * Results are always JSON *objects* (Capacitor resolves calls with objects): `Unit`/`null` becomes
 * `{}` and any non-object value `v` becomes `{"value": v}`.
 */
class KmpBridge(
    val module: KmpModule,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Hosts [module] on [Dispatchers.Default]. (Default arguments are not visible from Swift.) */
    constructor(module: KmpModule) : this(module, Dispatchers.Default)

    internal val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("KmpBridge"))
    private var eventListener: ((event: String, payloadJson: String) -> Unit)? = null

    init {
        module.attach(scope) { event, payload ->
            eventListener?.invoke(event, KmpJson.encodeToString(JsonElement.serializer(), payload.asResultObject()))
        }
    }

    /** Names of the methods registered by the module. */
    val methodNames: List<String> get() = module.methodNames

    /** Sets (or clears, with `null`) the listener receiving `(eventName, payloadJson)` for emitted events. */
    fun setEventListener(listener: ((event: String, payloadJson: String) -> Unit)?) {
        eventListener = listener
    }

    /**
     * Invokes [method] with [argsJson] (a JSON object, may be null/blank) on the bridge's scope.
     * Exactly one of [onSuccess] (JSON object string) or [onError] (code, message) is called, on a
     * background thread.
     */
    fun call(
        method: String,
        argsJson: String?,
        onSuccess: (resultJson: String) -> Unit,
        onError: (code: String, message: String) -> Unit,
    ) {
        scope.launch {
            val result = try {
                execute(method, argsJson)
            } catch (e: Throwable) {
                val error = e.toKmpException()
                onError(error.code, error.message ?: error.code)
                return@launch
            }
            onSuccess(result)
        }
    }

    /** Suspending variant of [call]; throws [KmpException] on failure. */
    suspend fun execute(method: String, argsJson: String?): String {
        val result = module.invoke(KmpCall.parse(method, argsJson))
        return KmpJson.encodeToString(JsonElement.serializer(), result.asResultObject())
    }

    /** Cancels all work started by the module and detaches it. The bridge cannot be used afterwards. */
    fun close() {
        eventListener = null
        module.detach()
        scope.cancel()
    }

    private fun JsonElement.asResultObject(): JsonObject = when (this) {
        is JsonObject -> this
        JsonNull -> KmpModule.EMPTY_RESULT
        else -> JsonObject(mapOf("value" to this))
    }

    private fun Throwable.toKmpException(): KmpException = when (this) {
        is KmpException -> this
        is CancellationException -> KmpException(KmpErrorCode.CANCELLED, message ?: "Cancelled", this)
        else -> KmpException(KmpErrorCode.INTERNAL, message ?: this::class.simpleName ?: "Unknown error", this)
    }
}
