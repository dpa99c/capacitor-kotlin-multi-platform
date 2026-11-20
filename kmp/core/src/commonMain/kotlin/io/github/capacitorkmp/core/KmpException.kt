package io.github.capacitorkmp.core

/**
 * Error raised by a [KmpModule] method. The [code] and [message] are passed to Capacitor's
 * `call.reject(message, code)` on every platform, so JavaScript receives `{ code, message }`.
 */
open class KmpException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Well-known error codes used by the framework. Plugins are free to define their own codes. */
object KmpErrorCode {
    const val UNIMPLEMENTED: String = "UNIMPLEMENTED"
    const val INVALID_ARGUMENT: String = "INVALID_ARGUMENT"
    const val UNAVAILABLE: String = "UNAVAILABLE"
    const val PERMISSION_DENIED: String = "PERMISSION_DENIED"
    const val CANCELLED: String = "CANCELLED"
    const val INTERNAL: String = "INTERNAL"
}
