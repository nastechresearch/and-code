package com.nastechresearch.andcode.runtime.nastech

import com.nastechresearch.andcode.core.api.OpenCodeSkill
import kotlinx.serialization.json.JsonObject

/** Optional management surface implemented by Nastech-backed runtime targets. */
interface NastechRuntimeControl {
    val dashboardUrl: String?

    suspend fun capabilities(): JsonObject

    suspend fun toolsets(): JsonObject

    suspend fun skills(): List<OpenCodeSkill>

    suspend fun steer(
        sessionId: String,
        message: String,
    ): Boolean
}
