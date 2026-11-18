package com.example.nodes

import com.example.lang.StudioCapability
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class StlPackageManifest(
    val packageName: String,
    val version: String,
    val author: String,
    val sha256Checksum: String,
    val isSigned: Boolean,
    val requiredCapabilities: Set<StudioCapability>,
    val definitions: List<CustomNodeDefinition>
)

/**
 * Exports and inspects `.stlpkg` ZIP bundles containing custom triggers, actions,
 * action blocks, source code, layout sidecars, and generated markdown docs.
 * Enforces zip-slip protection and never auto-grants capabilities on import.
 */
object StlPackageManager {

    fun exportPackage(
        packageName: String,
        version: String,
        author: String,
        definitions: List<CustomNodeDefinition>
    ): ByteArray {
        val combinedSource = definitions.joinToString("\n\n") { it.sourceCode }
        val checksum = sha256Hex(combinedSource.toByteArray(Charsets.UTF_8))
        val caps = definitions.flatMap { it.requiredCapabilities }.toSet()

        val manifest = JSONObject().apply {
            put("schemaVersion", 1)
            put("packageName", packageName)
            put("version", version)
            put("author", author)
            put("sha256", checksum)
            put("signed", false)
            put("capabilities", JSONArray(caps.map { it.id }))
            val defsArr = JSONArray()
            definitions.forEach { def ->
                defsArr.put(JSONObject().apply {
                    put("id", def.id)
                    put("name", def.name)
                    put("kind", def.kind.name)
                    put("language", def.language.name)
                    put("summary", def.summary)
                    put("returnType", def.returnType.name)
                    put("version", def.version)
                    put("capabilities", JSONArray(def.requiredCapabilities.map { it.id }))
                    val pArr = JSONArray()
                    def.params.forEach { p ->
                        pArr.put(JSONObject().apply {
                            put("key", p.key)
                            put("label", p.label)
                            put("type", p.type.name)
                            put("defaultValue", p.defaultValue)
                        })
                    }
                    put("params", pArr)
                })
            }
            put("definitions", defsArr)
        }

        val docsMd = buildString {
            appendLine("# $packageName v$version")
            appendLine("Author: $author")
            appendLine()
            definitions.forEach { d ->
                appendLine("## ${d.kind.name}: ${d.name}")
                appendLine(d.summary)
                if (d.requiredCapabilities.isNotEmpty()) {
                    appendLine("Needs: ${d.requiredCapabilities.joinToString { it.id }}")
                }
                appendLine("```stl")
                appendLine(d.sourceCode)
                appendLine("```")
                appendLine()
            }
        }

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("sources/definitions.stl"))
            zos.write(combinedSource.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("docs/README.md"))
            zos.write(docsMd.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    fun inspectPackageForReview(zipBytes: ByteArray): StlPackageManifest {
        var manifestJson: JSONObject? = null
        var sourceBytes: ByteArray? = null
        var totalUncompressed = 0L
        val maxBytes = 5L * 1024L * 1024L

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.contains("..") || entry.name.startsWith("/")) {
                    throw SecurityException("Malicious zip entry path detected: ${entry.name}")
                }
                val content = zis.readBytes()
                totalUncompressed += content.size
                if (totalUncompressed > maxBytes) {
                    throw SecurityException("Package uncompressed size exceeds 5 MB safety limit.")
                }
                if (entry.name == "manifest.json") {
                    manifestJson = JSONObject(String(content, Charsets.UTF_8))
                } else if (entry.name == "sources/definitions.stl") {
                    sourceBytes = content
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val manifest = manifestJson ?: throw IllegalArgumentException("Missing manifest.json in .stlpkg archive.")
        val actualSha = sourceBytes?.let { sha256Hex(it) }.orEmpty()
        val expectedSha = manifest.optString("sha256", "")
        if (expectedSha.isNotEmpty() && actualSha.isNotEmpty() && expectedSha != actualSha) {
            throw SecurityException("Package SHA-256 integrity mismatch.")
        }

        val caps = linkedSetOf<StudioCapability>()
        val capArr = manifest.optJSONArray("capabilities") ?: JSONArray()
        for (i in 0 until capArr.length()) {
            StudioCapability.fromId(capArr.getString(i))?.let { caps += it }
        }

        val defs = mutableListOf<CustomNodeDefinition>()
        val dArr = manifest.optJSONArray("definitions") ?: JSONArray()
        val sourceText = sourceBytes?.let { String(it, Charsets.UTF_8) }.orEmpty()
        for (i in 0 until dArr.length()) {
            val o = dArr.getJSONObject(i)
            val dCaps = linkedSetOf<StudioCapability>()
            val dcArr = o.optJSONArray("capabilities") ?: JSONArray()
            for (j in 0 until dcArr.length()) StudioCapability.fromId(dcArr.getString(j))?.let { dCaps += it }

            val params = mutableListOf<NodeParamSpec>()
            val pArr = o.optJSONArray("params") ?: JSONArray()
            for (j in 0 until pArr.length()) {
                val po = pArr.getJSONObject(j)
                params += NodeParamSpec(
                    key = po.getString("key"),
                    label = po.optString("label", po.getString("key")),
                    type = runCatching { PortType.valueOf(po.optString("type", "ANY")) }.getOrDefault(PortType.ANY),
                    defaultValue = po.optString("defaultValue", "")
                )
            }
            defs += CustomNodeDefinition(
                id = o.getString("id"),
                name = o.getString("name"),
                kind = runCatching { CustomDefinitionKind.valueOf(o.optString("kind", "BLOCK")) }.getOrDefault(CustomDefinitionKind.BLOCK),
                language = runCatching { BlockLanguage.valueOf(o.optString("language", "STUDIOLANG")) }.getOrDefault(BlockLanguage.STUDIOLANG),
                summary = o.optString("summary", ""),
                params = params,
                returnType = runCatching { PortType.valueOf(o.optString("returnType", "ANY")) }.getOrDefault(PortType.ANY),
                requiredCapabilities = dCaps,
                sourceCode = sourceText,
                version = o.optInt("version", 1)
            )
        }

        return StlPackageManifest(
            packageName = manifest.optString("packageName", "ImportedPackage"),
            version = manifest.optString("version", "1.0.0"),
            author = manifest.optString("author", "Unknown"),
            sha256Checksum = actualSha,
            isSigned = manifest.optBoolean("signed", false),
            requiredCapabilities = caps,
            definitions = defs
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
