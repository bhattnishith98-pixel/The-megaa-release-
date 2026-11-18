package com.example.agent

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

data class AttachmentValidationResult(
    val accepted: Boolean,
    val sanitizedText: String,
    val redactedSecretsCount: Int,
    val injectionWarnings: List<String>,
    val rejectionReason: String? = null
)

/**
 * Validates and sanitizes user attachments (files, logs, zip archives, images)
 * before they reach the Agent or AI model:
 * - Enforces file size and archive uncompressed limits (zip-bomb protection)
 * - Rejects zip-slip path traversal entries
 * - Redacts API keys, tokens, and password strings
 * - Scans for prompt-injection phrases inside untrusted attachment content
 */
object AttachmentValidator {

    const val MAX_TEXT_BYTES = 256 * 1024 // 256 KB per text/code file
    const val MAX_ZIP_UNCOMPRESSED_BYTES = 2 * 1024 * 1024 // 2 MB
    const val MAX_IMAGE_BYTES = 10 * 1024 * 1024 // 10 MB (< 20 MB Firebase AI limit)
    const val MAX_ATTACHMENTS_PER_BRIEF = 8

    private val secretPatterns = listOf(
        Regex("AIza[0-9A-Za-z_-]{20,}"),
        Regex("sk-[0-9A-Za-z]{16,}"),
        Regex("ghp_[0-9A-Za-z]{20,}"),
        Regex("Bearer\\s+[A-Za-z0-9._-]{12,}", RegexOption.IGNORE_CASE),
        Regex("(password|passwd|api_key|secret|token)\\s*[:=]\\s*[\"']?[^\\s\"']{4,}[\"']?", RegexOption.IGNORE_CASE)
    )

    private val injectionPatterns = listOf(
        Regex("ignore\\s+(all\\s+)?previous\\s+instructions", RegexOption.IGNORE_CASE),
        Regex("disregard\\s+(all\\s+)?system\\s+prompt", RegexOption.IGNORE_CASE),
        Regex("bypass\\s+(sandbox|permissions?|security)", RegexOption.IGNORE_CASE),
        Regex("grant\\s+all\\s+capabilities", RegexOption.IGNORE_CASE),
        Regex("exfiltrate|steal\\s+secrets|delete\\s+all\\s+files", RegexOption.IGNORE_CASE)
    )

    fun validateTextAttachment(fileName: String, rawBytes: ByteArray): AttachmentValidationResult {
        if (rawBytes.size > MAX_TEXT_BYTES) {
            return AttachmentValidationResult(
                accepted = false,
                sanitizedText = "",
                redactedSecretsCount = 0,
                injectionWarnings = emptyList(),
                rejectionReason = "File '$fileName' exceeds ${MAX_TEXT_BYTES / 1024} KB limit."
            )
        }
        val text = rawBytes.toString(Charsets.UTF_8)
        return sanitizeContent(fileName, text)
    }

    fun validateZipAttachment(fileName: String, zipBytes: ByteArray): AttachmentValidationResult {
        val sb = StringBuilder()
        var totalSize = 0L
        var fileCount = 0
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.contains("..") || entry.name.startsWith("/")) {
                        return AttachmentValidationResult(
                            accepted = false,
                            sanitizedText = "",
                            redactedSecretsCount = 0,
                            injectionWarnings = emptyList(),
                            rejectionReason = "Zip-slip path traversal rejected in '$fileName': ${entry.name}"
                        )
                    }
                    if (!entry.isDirectory) {
                        val data = zis.readBytes()
                        totalSize += data.size
                        if (totalSize > MAX_ZIP_UNCOMPRESSED_BYTES) {
                            return AttachmentValidationResult(
                                accepted = false,
                                sanitizedText = "",
                                redactedSecretsCount = 0,
                                injectionWarnings = emptyList(),
                                rejectionReason = "Archive '$fileName' exceeds ${MAX_ZIP_UNCOMPRESSED_BYTES / 1024} KB uncompressed safety limit."
                            )
                        }
                        fileCount++
                        if (fileCount <= 25 && isTextLike(entry.name)) {
                            sb.appendLine("--- FILE: ${entry.name} ---")
                            sb.appendLine(data.toString(Charsets.UTF_8).take(8000))
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return AttachmentValidationResult(
                accepted = false,
                sanitizedText = "",
                redactedSecretsCount = 0,
                injectionWarnings = emptyList(),
                rejectionReason = "Invalid archive '$fileName': ${e.message}"
            )
        }
        return sanitizeContent(fileName, sb.toString())
    }

    fun sanitizeContent(sourceLabel: String, content: String): AttachmentValidationResult {
        var redacted = content
        var redactCount = 0
        for (regex in secretPatterns) {
            val matches = regex.findAll(redacted).count()
            if (matches > 0) {
                redactCount += matches
                redacted = regex.replace(redacted, "[REDACTED_SECRET]")
            }
        }

        val warnings = mutableListOf<String>()
        for (inj in injectionPatterns) {
            if (inj.containsMatchIn(redacted)) {
                warnings += "Suspicious directive detected inside untrusted content '$sourceLabel' (${inj.pattern}); treated strictly as data."
            }
        }

        return AttachmentValidationResult(
            accepted = true,
            sanitizedText = redacted,
            redactedSecretsCount = redactCount,
            injectionWarnings = warnings
        )
    }

    fun redactSecretsOnly(text: String): String {
        var out = text
        for (regex in secretPatterns) {
            out = regex.replace(out, "[REDACTED_SECRET]")
        }
        return out
    }

    private fun isTextLike(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".stl") || lower.endsWith(".md") || lower.endsWith(".txt") ||
            lower.endsWith(".json") || lower.endsWith(".kt") || lower.endsWith(".xml") ||
            lower.endsWith(".kts") || lower.endsWith(".csv") || lower.endsWith(".log")
    }
}
