package io.github.capacitorkmp.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KmpBridgeTest {

    @Serializable
    data class AddOptions(val a: Int, val b: Int)

    @Serializable
    data class Sum(val sum: Int)

    private class TestModule : KmpModule() {
        init {
            method("add") { decode<AddOptions>().let { Sum(it.a + it.b) } }
            method("count") { 42 }
            method("nothing") { }
            method<Unit>("fail") { throw KmpException("CUSTOM", "boom") }
            method<Unit>("crash") { error("unexpected") }
            method("ping") { emit("pong", Sum(getInt("n") ?: 0)) }
        }
    }

    private val bridge = KmpBridge(TestModule())

    @AfterTest
    fun tearDown() = bridge.close()

    private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject

    @Test
    fun decodesTypedOptionsAndEncodesResult() = runTest {
        val result = bridge.execute("add", """{"a":2,"b":3}""").json()
        assertEquals(5, result["sum"]!!.jsonPrimitive.int)
    }

    @Test
    fun wrapsNonObjectResults() = runTest {
        assertEquals(42, bridge.execute("count", null).json()["value"]!!.jsonPrimitive.int)
        assertEquals(JsonObject(emptyMap()), bridge.execute("nothing", "").json())
    }

    @Test
    fun unknownMethodIsUnimplemented() = runTest {
        val e = assertFailsWith<KmpException> { bridge.execute("missing", "{}") }
        assertEquals(KmpErrorCode.UNIMPLEMENTED, e.code)
    }

    @Test
    fun invalidOptionsAreRejected() = runTest {
        assertEquals(KmpErrorCode.INVALID_ARGUMENT, assertFailsWith<KmpException> { bridge.execute("add", """{"a":1}""") }.code)
        assertEquals(KmpErrorCode.INVALID_ARGUMENT, assertFailsWith<KmpException> { bridge.execute("add", "not json") }.code)
    }

    @Test
    fun callbackApiReportsErrorsWithCodes() = runTest {
        val custom = CompletableDeferred<Pair<String, String>>()
        bridge.call("fail", "{}", { custom.completeExceptionally(AssertionError(it)) }, { c, m -> custom.complete(c to m) })
        assertEquals("CUSTOM" to "boom", custom.await())

        val internal = CompletableDeferred<String>()
        bridge.call("crash", "{}", { internal.completeExceptionally(AssertionError(it)) }, { c, _ -> internal.complete(c) })
        assertEquals(KmpErrorCode.INTERNAL, internal.await())
    }

    @Test
    fun eventsAreForwardedAsJson() = runTest {
        val received = CompletableDeferred<Pair<String, String>>()
        bridge.setEventListener { event, payload -> received.complete(event to payload) }
        bridge.execute("ping", """{"n":7}""")
        val (event, payload) = received.await()
        assertEquals("pong", event)
        assertEquals(7, payload.json()["sum"]!!.jsonPrimitive.int)
    }

    @Test
    fun coreInfoModuleReportsPlatform() = runTest {
        val core = KmpBridge(CoreInfoModule())
        try {
            val info = core.execute("getKmpInfo", null).json()
            assertTrue(info["platform"]!!.jsonPrimitive.content in setOf("android", "ios", "web"))
            assertEquals("hi", core.execute("echo", """{"value":"hi"}""").json()["value"]!!.jsonPrimitive.content)
            assertEquals(listOf("getKmpInfo", "echo"), core.methodNames)
        } finally {
            core.close()
        }
    }
}
