package com.nastechresearch.andcode.runtime.nastech

import com.nastechresearch.andcode.core.api.ConfiguredProvider
import com.nastechresearch.andcode.core.api.OpenCodeAgent
import com.nastechresearch.andcode.core.api.OpenCodeCommand
import com.nastechresearch.andcode.core.api.OpenCodeEvent
import com.nastechresearch.andcode.core.api.OpenCodeHealth
import com.nastechresearch.andcode.core.api.OpenCodeMessage
import com.nastechresearch.andcode.core.api.OpenCodeSession
import com.nastechresearch.andcode.core.api.PermissionRequest
import com.nastechresearch.andcode.core.api.PromptRequest
import com.nastechresearch.andcode.core.api.ProviderCatalog
import com.nastechresearch.andcode.core.api.QuestionRequest
import com.nastechresearch.andcode.data.connection.ConnectionProfile
import com.nastechresearch.andcode.runtime.BackendKind
import com.nastechresearch.andcode.runtime.PermissionResponse
import com.nastechresearch.andcode.runtime.RuntimeCapabilities
import com.nastechresearch.andcode.runtime.RuntimeState
import com.nastechresearch.andcode.runtime.RuntimeTarget
import com.nastechresearch.andcode.runtime.RuntimeType
import com.nastechresearch.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** AndCode adapter for the complete Nastech-Agent product. It does not embed Nastech's brain. */
class NastechRuntimeTarget(
    val profile: ConnectionProfile,
    private val api: NastechApiClient = NastechApiClient(profile),
) : RuntimeTarget, NastechRuntimeControl {
    override val id: String = profile.id
    override val displayName: String = profile.name
    override val type: RuntimeType = RuntimeType.REMOTE
    override val kind: BackendKind = BackendKind.REMOTE
    override val dashboardUrl: String? = profile.dashboardUrl
    override val capabilities =
        RuntimeCapabilities(
            permissions = true,
            toolEvents = true,
            providerModelList = true,
            resume = true,
            abortsBeforeInterrupt = true,
        )

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    override suspend fun connect(): Result<OpenCodeHealth> {
        mutableState.value = RuntimeState.Connecting
        return runCatching { api.health() }
            .onSuccess { health ->
                mutableState.value =
                    if (health.healthy) {
                        RuntimeState.Connected(
                            health.version,
                        )
                    } else {
                        RuntimeState.Failed("Nastech-Agent reported unhealthy")
                    }
            }
            .onFailure { error -> mutableState.value = RuntimeState.Failed(error.message ?: "Nastech connection failed") }
    }

    override fun disconnect() {
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun listWorkspaces(): List<WorkspaceRef> = emptyList()

    override suspend fun health(): OpenCodeHealth = api.health()

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> = api.sessions()

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = api.createSession(title)

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = api.messages(sessionId)

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession = api.renameSession(sessionId, title)

    override suspend fun deleteSession(sessionId: String): Boolean = api.deleteSession(sessionId)

    override suspend fun listProviders(): ProviderCatalog = api.providers()

    override suspend fun configProviders(): List<ConfiguredProvider> =
        api.providers().all.map { provider ->
            ConfiguredProvider(
                id = provider.id,
                name = provider.name,
                defaultModel = provider.models.values.firstOrNull()?.id,
                connected = provider.id in api.providers().connected,
            )
        }

    override suspend fun skills() = api.skills()

    override suspend fun capabilities() = api.capabilities()

    override suspend fun toolsets() = api.toolsets()

    override suspend fun steer(
        sessionId: String,
        message: String,
    ): Boolean = api.steer(sessionId, message)

    override suspend fun config(): JsonElement =
        buildJsonObject {
            put("capabilities", api.capabilities())
            put("gateway", api.detailedHealth())
            put("toolsets", api.toolsets())
        }

    override suspend fun commands(): List<OpenCodeCommand> =
        listOf(
            OpenCodeCommand("setup", "Configure Nastech-Agent providers, terminal, gateway, and tools"),
            OpenCodeCommand("doctor", "Run Nastech-Agent diagnostics"),
            OpenCodeCommand("gateway", "Configure Telegram and other gateway platforms"),
            OpenCodeCommand("tools", "Configure Nastech toolsets"),
            OpenCodeCommand("update", "Update the Nastech-Agent runtime"),
        )

    override suspend fun listAgents(): List<OpenCodeAgent> = listOf(OpenCodeAgent("nastech", "Nastech-Agent", "primary", true))

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) = api.send(sessionId, request)

    override suspend fun abortSession(sessionId: String): Boolean = api.abort(sessionId)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = api.approve(sessionId, permissionId, response != PermissionResponse.REJECT)

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = unsupported("Nastech questions")

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = unsupported("Nastech question rejection")

    override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = emptyList()

    override fun events(): Flow<OpenCodeEvent> =
        channelFlow {
            api.activeRunSnapshot().forEach { (sessionId, runId) ->
                launch {
                    api.runEvents(runId).collect { event ->
                        val type = event["event"]?.toString()?.trim('"') ?: return@collect
                        when (type) {
                            "approval.request" -> {
                                val requestId =
                                    event["request_id"]?.toString()?.trim('"')
                                        ?: event["id"]?.toString()?.trim('"')
                                        ?: runId
                                send(
                                    OpenCodeEvent.PermissionAsked(
                                        PermissionRequest(
                                            id = requestId,
                                            sessionId = sessionId,
                                            permission = event["permission"]?.toString()?.trim('"') ?: "approval",
                                            patterns = event["command"]?.toString()?.trim('"')?.let(::listOf).orEmpty(),
                                            metadata = event,
                                        ),
                                    ),
                                )
                            }
                            "run.completed", "run.cancelled", "run.failed" -> {
                                send(OpenCodeEvent.SessionStatusChanged(sessionId, type.removePrefix("run.")))
                                api.forgetRun(sessionId)
                            }
                            else -> send(OpenCodeEvent.SessionStatusChanged(sessionId, type))
                        }
                    }
                }
            }
        }

    private fun unsupported(capability: String): Nothing = throw UnsupportedOperationException(capability)
}
