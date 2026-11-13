package com.foxislam.androidagent.agent

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OpenRouterBackendTest {
    private fun reply(name: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: " + JSONObject().put("model", "\n这是 OpenRouter 的回复")
            .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()
                .put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "call_1")
                    .put("function", JSONObject().put("name", name).put("arguments", "{}"))))))) +
            "\n\ndata: [DONE]\n\n")

    private fun done() = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"role":"assistant","content":"完成"},"finish_reason":"stop"}]}""")

    @Test
    fun `free route stays unchanged after streamed tools including screenshots`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = OpenRouterBackend(server.url("/api/v1/chat/completions").toString(), "test", "openrouter/free", Reasoning.DEFAULT)
            for (name in listOf("open_app", "ui_tree", "screenshot")) {
                server.enqueue(reply(name))
                server.enqueue(done())
                val turns = mutableListOf<Turn>(Turn.User("打开应用，查看界面\n这是 OpenRouter 测试"))
                val specs = listOf(ToolSpec(name, "测试工具"))
                val assistant = backend.next("中文系统提示", turns, specs)
                val first = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                assertEquals("openrouter/free", first.getString("model"))
                turns += assistant
                val call = assistant.calls.single()
                val outcome = if (name == "screenshot") Outcome.Image("aGVsbG8=", "屏幕截图") else Outcome.Text("\n这是 OpenRouter 工具结果")
                turns += Turn.ToolResults(listOf(ToolOutput(call, outcome)))
                val route = Router("").route(Router.State(1, false, false, false, true))
                backend.next("中文系统提示", turns, specs, route)
                val second = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                assertEquals("openrouter/free", second.getString("model"))
                assertFalse(second.has("reasoning"))
                val messages = second.getJSONArray("messages")
                assertEquals(name, messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
                assertEquals("tool", messages.getJSONObject(3).getString("role"))
                assertEquals(call.id, messages.getJSONObject(3).getString("tool_call_id"))
                if (name == "screenshot") {
                    assertEquals("user", messages.getJSONObject(4).getString("role"))
                    assertEquals("data:image/jpeg;base64,aGVsbG8=", messages.getJSONObject(4).getJSONArray("content").getJSONObject(0).getJSONObject("image_url").getString("url"))
                }
            }
        }
    }

    @Test
    fun `assistant without provider echo keeps tool call when replayed`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(done())
            val call = ToolCall("restored", "ui_tree", mapOf("text" to "中文\n内容"))
            val backend = OpenRouterBackend(server.url("/").toString(), "test", "openrouter/free")
            backend.next("system", listOf(Turn.User("查看屏幕"), Turn.Assistant(null, null, listOf(call), null),
                Turn.ToolResults(listOf(ToolOutput(call, Outcome.Text("tree"))))), listOf(ToolSpec("ui_tree", "tree")))
            val messages = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
            val echoed = messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0)
            assertEquals("restored", echoed.getString("id"))
            assertEquals("中文\n内容", JSONObject(echoed.getJSONObject("function").getString("arguments")).getString("text"))
        }
    }

    @Test
    fun `blank override uses main model and explicit free override stays free`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = OpenRouterBackend(server.url("/").toString(), "test", " openrouter/free ", fastModel = " ")
            for (override in listOf(null, "", "  ", " vendor/model:free ")) {
                server.enqueue(done())
                backend.next("system", listOf(Turn.User("你好")), emptyList(), override)
                val sent = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                assertEquals(if (override?.contains(":free") == true) "vendor/model:free" else "openrouter/free", sent.getString("model"))
            }
            server.enqueue(done())
            assertEquals("完成", backend.complete("system", "summary"))
            assertEquals("openrouter/free", JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getString("model"))
        }
    }

    @Test
    fun `prose in a model setting fails locally without making a request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (invalid in listOf("", "openrouter/free\n这是 Op", "这是 Op", "openrouter/free\u0000")) {
                // A missing guard must fail the assertion promptly, not wait for an HTTP timeout.
                server.enqueue(done())
                val backend = OpenRouterBackend(server.url("/").toString(), "test", invalid)
                assertFailsWith<LlmException> { backend.next("system", emptyList(), emptyList()) }
            }
            val backend = OpenRouterBackend(server.url("/").toString(), "test", "openrouter/free", fastModel = "bad model")
            server.enqueue(done())
            assertFailsWith<LlmException> { backend.complete("system", "summary") }
            server.enqueue(done())
            assertFailsWith<LlmException> { backend.next("system", emptyList(), emptyList(), "bad model") }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `400 identifies actual model and request ID without retry or paid fallback`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(400).setHeader("X-Request-Id", "req-test")
                .setBody("""{"error":{"message":"\\n这是 Op is not a valid model ID","code":400}}"""))
            val backend = OpenRouterBackend(server.url("/").toString(), "secret-test", "openrouter/free")
            val error = assertFailsWith<LlmException> { backend.next("private prompt", emptyList(), emptyList()) }
            assertContains(error.message!!, "model=\"openrouter/free\"")
            assertContains(error.message!!, "request_id=\"req-test\"")
            assertFalse(error.message!!.contains("secret-test"))
            assertFalse(error.message!!.contains("private prompt"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `provider reasoning details survive tool result replay`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val details = JSONArray().put(JSONObject().put("type", "reasoning.encrypted")
                .put("id", "reasoning_1").put("data", "opaque-provider-data").put("format", "test"))
            val message = JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put("reasoning_details", details).put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", "call_reasoning").put("type", "function").put("function", JSONObject()
                        .put("name", "ui_tree").put("arguments", "{}"))))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", message).put("finish_reason", "tool_calls"))).toString()))
            server.enqueue(done())
            val backend = OpenRouterBackend(server.url("/").toString(), "test", "openrouter/free", Reasoning.DEFAULT)
            val turns = mutableListOf<Turn>(Turn.User("查看屏幕"))
            val assistant = backend.next("system", turns, listOf(ToolSpec("ui_tree", "tree")))
            server.takeRequest(5, TimeUnit.SECONDS)!!
            turns += assistant
            turns += Turn.ToolResults(listOf(ToolOutput(assistant.calls.single(), Outcome.Text("tree"))))
            backend.next("system", turns, listOf(ToolSpec("ui_tree", "tree")))
            val sent = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals("openrouter/free", sent.getString("model"))
            assertEquals(details.toString(), sent.getJSONArray("messages").getJSONObject(2).getJSONArray("reasoning_details").toString())
            assertFalse(sent.has("reasoning"))
        }
    }

    @Test
    fun `paid vision models preserve tools and PNG screenshots across requests`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val specs = listOf("open_app", "ui_tree", "screenshot").map { ToolSpec(it, "测试工具") }
            for (model in listOf("openai/gpt-4o-mini", "google/gemini-2.5-flash")) {
                server.enqueue(reply("screenshot"))
                server.enqueue(done())
                val backend = OpenRouterBackend(server.url("/api/v1/chat/completions").toString(), "test", model, Reasoning.DEFAULT)
                val turns = mutableListOf<Turn>(Turn.User("请截图并识别中文界面"))
                val assistant = backend.next("system", turns, specs)
                val first = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                assertEquals(model, first.getString("model"))
                val call = assistant.calls.single()
                assertEquals("screenshot", call.name)
                val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0woAAAAASUVORK5CYII="
                turns += assistant
                turns += Turn.ToolResults(listOf(ToolOutput(call, Outcome.Image(png, "截图", "image/png"))))
                backend.next("system", turns, specs)
                val sent = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                assertEquals(model, sent.getString("model"))
                assertEquals(3, sent.getJSONArray("tools").length())
                assertEquals("auto", sent.getString("tool_choice"))
                assertFalse(sent.has("reasoning"))
                val messages = sent.getJSONArray("messages")
                assertEquals(call.id, messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
                assertEquals("tool", messages.getJSONObject(3).getString("role"))
                assertEquals(call.id, messages.getJSONObject(3).getString("tool_call_id"))
                assertEquals("user", messages.getJSONObject(4).getString("role"))
                assertEquals("data:image/png;base64,$png", messages.getJSONObject(4).getJSONArray("content")
                    .getJSONObject(0).getJSONObject("image_url").getString("url"))
            }
        }
    }

}
