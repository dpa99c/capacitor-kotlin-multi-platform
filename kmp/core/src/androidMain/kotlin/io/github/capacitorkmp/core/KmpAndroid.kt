package io.github.capacitorkmp.core

import android.content.Context

/**
 * Holds the Android application [Context] for shared code whose `actual` implementations need it
 * (system services, preferences, …). [KmpCapacitorPlugin] initialises it automatically in `load()`.
 */
object KmpAndroid {
    @Volatile
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext ?: context
    }

    val isInitialized: Boolean get() = context != null

    val applicationContext: Context
        get() = context ?: error("KmpAndroid.init(context) has not been called (KmpCapacitorPlugin does this in load())")
}
