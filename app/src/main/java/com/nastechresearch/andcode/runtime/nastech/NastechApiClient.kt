package com.nastechresearch.andcode.runtime.nastech

import com.nastechresearch.andcode.core.api.OpenCodeHealth
import com.nastechresearch.andcode.core.api.OpenCodeMessage
import com.nastechresearch.andcode.core.api.OpenCodeMessageInfo
import com.nastechresearch.andcode.core.api.OpenCodeModel
import com.nastechresearch.andcode.core.api.OpenCodePart
import com.nastechresearch.andcode.core.api.OpenCodeProvider
import com.nastechresearch.andcode.core.api.OpenCodeSession
import com.nastechresearch.andcode.core.api.OpenCodeTime
import com.nastechresearch.andcode.core.api.PromptRequest
import com.nastechresearch.andcode.core.api.ProviderCatalog
import com.nastechresearch.andcode.core.security.OpenCodeUrl
import com.nastechresearch.andcode.data.connection.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Thin adapter over Nastech-Agent's public API. Nastech remains the source of truth. */
class NastechApiClient(
    private val profile: ConnectionProfile,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    private val baseUrl by lazy { OpenCodeUrl.normalize(profile.baseUrl).getOrThrow() }
    private val activeRuns = ConcurrentHashMap<String, String>()

    suspend fun health(): OpenCodeHealth {
        val root = get("health")
        return OpenCodeHealth(
            healthy = root["status"]?.jsonPrimitive?.contentOrNull == "ok",
            version = root["version"]?.jsonPrimitive?.contentOrNull ?: "unknown",
        )
    }

    suspend fun sessions(): List<OpenCodeSession> = get("api/sessions")["data"]?.jsonArray?.map(::sessionFromJson).orEmpty()

    suspend fun createSession(title: String?): OpenCodeSession {
        val body = buildJsonObject { title?.takeIf(String::isNotBlank)?.let { put("title", it) } }
        return sessionFromJson(post("api/sessions", body)["session"] ?: error("Nastech did not return a session"))
    }

    suspend fun messages(sessionId: String): List<OpenCodeMessage> =
        get("api/sessions/${encode(sessionId)}/messages")["data"]?.jsonArray?.map(::messageFromJson).orEmpty()

    /** Starts a durable Nastech run and returns immediately so AndCode can poll the transcript. */
    suspend fun send(
        sessionId: String,
        request: PromptRequest,
    ) {
        val body =
            buildJsonObject {
                put("input", request.text)
                put("session_id", sessionId)
                request.modelId?.takeIf(String::isNotBlank)?.let { put("model", it) }
                request.agent?.takeIf(String::isNotBlank)?.let { put("agent_ref", it) }
            }
        val runId =
            post("v1/runs", body)["run_id"]?.jsonPrimitive?.contentOrNull
                ?: error("Nastech did not return a run id")
        activeRuns[sessionId] = runId
    }

    suspend fun abort(sessionId: String): Boolean {
        val runId = activeRuns[sessionId] ?: return false
        return runCatching {
            post("v1/runs/${encode(runId)}/stop", buildJsonObject {})
            activeRuns.remove(sessionId, runId)
            true
        }.getOrDefault(false)
    }

    suspend fun providers(): ProviderCatalog {
        val models =
            get("v1/models")["data"]?.jsonArray.orEmpty()
                .mapNotNull { model -> model.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .associateWith { id -> OpenCodeModel(id = id, providerId = "nastech", name = id) }
        return ProviderCatalog(
            all =
                listOf(
                    OpenCodeProvider(id = "nastech", name = "Nastech-Agent", models = models),
                ),
            default =
                models.keys.firstOrNull()?.let {
                    mapOf("nastech" to it)
                }.orEmpty(),
            connected = listOf("nastech"),
        )
    }

    suspend fun capabilities(): JsonObject = get("v1/capabilities")

    private suspend fun get(path: String): JsonObject = request("GET", path, null).jsonObject

    private suspend fun post(
        path: String,
        body: JsonObject,
    ): JsonObject = request("POST", path, body).jsonObject

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject?,
    ): JsonElement =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(baseUrl.newBuilder().addPathSegments(path).build())
            if (!profile.apiKey.isNullOrBlank()) {
                builder.header("Authorization", "Bearer ${profile.apiKey}")
            } else if (!profile.password.isNullOrBlank()) {
                builder.header("Authorization", okhttp3.Credentials.basic(profile.username, profile.password))
            }
            if (body != null) {
                builder.header("Content-Type", JSON_MEDIA_TYPE.toString())
                builder.method(method, body.toString().toRequestBody(JSON_MEDIA_TYPE))
            } else {
                builder.method(method, null)
            }
            httpClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Nastech ${response.code}: ${text.take(300)}")
                if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
            }
        }

    private fun sessionFromJson(element: JsonElement): OpenCodeSession {
        val value = element.jsonObject
        val started = value.long("started_at")
        val updated = value.long("last_active") ?: started
        return OpenCodeSession(
            id = value.string("id") ?: error("Nastech session has no id"),
            title = value.string("title") ?: "Nastech session",
            time = OpenCodeTime(created = epochMs(started ?: 0L), updated = updated?.let(::epochMs)),
            version = "nastech-agent",
        )
    }

    private fun messageFromJson(element: JsonElement): OpenCodeMessage {
        val value = element.jsonObject
        val id = value.string("id") ?: "nastech-message-${value.hashCode()}"
        val role = value.string("role") ?: "assistant"
        return OpenCodeMessage(
            info =
                OpenCodeMessageInfo(
                    id = id,
                    sessionId = value.string("session_id") ?: "",
                    role = role,
                    time = OpenCodeTime(created = epochMs(value.long("timestamp") ?: 0L)),
                ),
            parts = listOf(OpenCodePart(id = id, type = "text", text = content(value["content"]))),
        )
    }

    private fun content(element: JsonElement?): String =
        when (element) {
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            is JsonArray -> element.joinToString("\n") { content(it.jsonObject["text"] ?: it.jsonObject["content"]) }
            is JsonObject -> content(element["text"] ?: element["content"]).ifBlank { element.toString() }
            else -> ""
        }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

    private fun epochMs(value: Long): Long = if (value in 1 until 1_000_000_000_000L) value * 1000L else value

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }
}
