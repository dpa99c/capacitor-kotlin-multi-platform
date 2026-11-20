package io.github.capacitorkmp.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A single invocation of a plugin method: the method name plus the options object passed from
 * JavaScript (`MyPlugin.method(options)`), with typed accessors.
 */
class KmpCall(
    val method: String,
    val args: JsonObject,
) {
    /** Decodes the whole options object into a `@Serializable` type. */
    inline fun <reified T> decode(): T = try {
        KmpJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), args)
    } catch (e: SerializationException) {
        throw KmpException(KmpErrorCode.INVALID_ARGUMENT, "Invalid options for '$method': ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw KmpException(KmpErrorCode.INVALID_ARGUMENT, "Invalid options for '$method': ${e.message}", e)
    }

    fun has(key: String): Boolean = args[key].let { it != null && it !is JsonNull }

    fun getString(key: String): String? = primitive(key)?.takeIf { it.isString }?.content
    fun getBoolean(key: String): Boolean? = primitive(key)?.booleanOrNull
    fun getInt(key: String): Int? = primitive(key)?.intOrNull
    fun getLong(key: String): Long? = primitive(key)?.longOrNull
    fun getDouble(key: String): Double? = primitive(key)?.doubleOrNull
    fun getObject(key: String): JsonObject? = args[key] as? JsonObject

    fun requireString(key: String): String = getString(key) ?: missing(key, "string")
    fun requireBoolean(key: String): Boolean = getBoolean(key) ?: missing(key, "boolean")
    fun requireInt(key: String): Int = getInt(key) ?: missing(key, "integer")
    fun requireDouble(key: String): Double = getDouble(key) ?: missing(key, "number")

    private fun primitive(key: String): JsonPrimitive? = args[key] as? JsonPrimitive
    private fun missing(key: String, type: String): Nothing =
        throw KmpException(KmpErrorCode.INVALID_ARGUMENT, "'$method' requires a $type option '$key'")

    override fun toString(): String = "KmpCall($method, $args)"

    internal companion object {
        fun parse(method: String, argsJson: String?): KmpCall {
            if (argsJson.isNullOrBlank()) return KmpCall(method, JsonObject(emptyMap()))
            val element: JsonElement = try {
                KmpJson.parseToJsonElement(argsJson)
            } catch (e: SerializationException) {
                throw KmpException(KmpErrorCode.INVALID_ARGUMENT, "Options for '$method' are not valid JSON", e)
            }
            return KmpCall(method, element as? JsonObject ?: JsonObject(emptyMap()))
        }
    }
}
