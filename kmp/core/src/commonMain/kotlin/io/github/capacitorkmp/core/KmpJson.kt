package io.github.capacitorkmp.core

import kotlinx.serialization.json.Json

/** The [Json] instance used for every value crossing the Capacitor ⇄ Kotlin boundary. */
val KmpJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}
