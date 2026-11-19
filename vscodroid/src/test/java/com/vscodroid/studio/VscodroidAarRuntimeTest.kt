package com.vscodroid.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VscodroidAarRuntimeTest {

    @Test
    fun vscodroidRuntimeReportsUpstreamAndToolchains() {
        val tempDir = Files.createTempDirectory("vscodroid_aar_test").toFile()
        try {
            val libs = File(tempDir, "libs").apply { mkdirs() }
            File(libs, "vscodroid-addon.aar").writeText("aar-payload")

            val status = VscodroidAarRuntime.inspectStatus(listOf(tempDir))
            assertEquals(":vscodroid", status.libraryModule)
            assertEquals("com.vscodroid.studio", status.packageNamespace)
            assertEquals("https://github.com/rmyndharis/VSCodroid", status.upstreamRepo)
            assertTrue(status.toolchains.any { it == VscodroidToolchainKind.PYTHON })
            assertTrue(status.toolchains.any { it == VscodroidToolchainKind.NODE_JS })
            assertTrue(status.discoveredExternalAars.contains("vscodroid-addon.aar"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun languageResolverAndBufferSyncWork() {
        assertEquals("python", VscodroidAarRuntime.resolveLanguageId("scripts/bot.py"))
        assertEquals("studiolang", VscodroidAarRuntime.resolveLanguageId("macros/auto.stl"))
        assertEquals("kotlin", VscodroidAarRuntime.resolveLanguageId("MainActivity.kt"))
        assertEquals("gdscript", VscodroidAarRuntime.resolveLanguageId("player.gd"))

        val sync = VscodroidAarRuntime.buildBufferMetadata("scripts/bot.py", "x = 1\nprint(x)")
        assertEquals("python", sync.languageId)
        assertEquals(2, sync.lineCount)
        assertEquals(16, sync.sha256Hex.length)
    }
}
