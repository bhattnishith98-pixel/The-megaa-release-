package com.example.agent

import android.graphics.Bitmap
import com.example.lang.StudioCapability

enum class AgentStatus(val label: String) {
    IDLE("Idle"),
    OBSERVING("Observing"),
    PLANNING("Planning"),
    WAITING_APPROVAL("Waiting for you"),
    ACTING("Acting"),
    VERIFYING("Verifying"),
    PAUSED("Paused"),
    ERROR("Error")
}

enum class AttachmentKind {
    IMAGE,
    SCREENSHOT,
    FILE,
    FOLDER,
    IN_APP_REF
}

data class BriefAttachment(
    val id: String,
    val kind: AttachmentKind,
    val displayName: String,
    val sanitizedTextContent: String = "",
    val bitmap: Bitmap? = null,
    val injectionWarnings: List<String> = emptyList(),
    val redactedSecretsCount: Int = 0,
    val includeInSend: Boolean = true
)

data class AgentToolCallSpec(
    val toolName: String,
    val args: Map<String, String>
)

data class AgentPlan(
    val id: String,
    val summary: String,
    val steps: List<String>,
    val toolCalls: List<AgentToolCallSpec>,
    val proposedSourceCode: String? = null,
    val requiredCapabilities: Set<StudioCapability> = emptySet(),
    val clarificationQuestion: String? = null
)

data class AgentToolLogEntry(
    val id: String,
    val timestampMs: Long,
    val toolName: String,
    val argsRedacted: String,
    val success: Boolean,
    val output: String
)

data class AgentMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val attachmentNames: List<String> = emptyList(),
    val plan: AgentPlan? = null,
    val timestampMs: Long = System.currentTimeMillis()
)
