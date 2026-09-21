package com.nastechresearch.andcode.runtime.nastech

import com.nastechresearch.andcode.core.api.PromptRequest
import com.nastechresearch.andcode.data.connection.ConnectionProfile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NastechApiClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `health reads Nastech version and sends bearer credential`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("{\"status\":\"ok\",\"platform\":\"nastech-agent\",\"version\":\"0.21.0\"}"))
            val client = NastechApiClient(profile())

            val health = client.health()
            val request = server.takeRequest()

            assertTrue(health.healthy)
            assertEquals("0.21.0", health.version)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            assertEquals("/health", request.path)
        }

    @Test
    fun `send starts a durable session run and records the session id`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("{\"run_id\":\"run_123\",\"status\":\"queued\"}"))
            val client = NastechApiClient(profile())

            client.send("session_1", PromptRequest(text = "hello", modelId = "nastech/default"))
            val request = server.takeRequest()
            val body = request.body.readUtf8()

            assertEquals("/v1/runs", request.path)
            assertTrue(body.contains("\"session_id\":\"session_1\""))
            assertTrue(body.contains("\"input\":\"hello\""))
            assertTrue(body.contains("\"model\":\"nastech/default\""))
        }

    private fun profile() =
        ConnectionProfile(
            name = "Nastech test",
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            runtime = "nastech",
        )
}
