package com.tom.rv2ide.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AcsAarRuntimeTest {

    @Test
    fun acsAarRuntimeInspectsStatusAndDiscoversWorkspaceAarFiles() {
        val tempDir = Files.createTempDirectory("acs_aar_test").toFile()
        try {
            val libsDir = File(tempDir, "libs").apply { mkdirs() }
            File(libsDir, "custom-plugin.aar").writeText("stub")
            File(libsDir, "helper.jar").writeText("stub")

            val status = AcsAarRuntime.inspectStatus(listOf(tempDir))
            assertEquals(":android-code-studio", status.libraryModule)
            assertEquals("com.tom.rv2ide.studio", status.packageNamespace)
            assertTrue(status.discoveredExternalAars.contains("custom-plugin.aar"))
            assertTrue(status.discoveredExternalAars.contains("helper.jar"))
            assertTrue(AcsAarRuntime.isSoraEditorAvailable())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun resourceIndexerAndXmlInflaterResolveStringsAndWidgets() {
        val table = AcsResourceIndexer.indexFiles(
            listOf(
                AcsSourceFile(
                    path = "res/values/strings.xml",
                    title = "strings.xml",
                    content = """<resources><string name="go_btn">Launch Game</string></resources>"""
                )
            )
        )
        assertEquals("Launch Game", table.strings["go_btn"])
        val widgets = AcsXmlLayoutInflater.inflateXmlLayout(
            """<LinearLayout><Button android:id="@+id/btn_go" android:text="@string/go_btn" /></LinearLayout>""",
            table
        )
        assertEquals(1, widgets.size)
        assertEquals("BUTTON", widgets.first().kind)
        assertEquals("Launch Game", widgets.first().label)
    }

    @Test
    fun kotlinJavaCompletionDetectsAndFixesMissingComposeImports() {
        val raw = """
            package com.example.demo
            @Composable
            fun Demo() {
                Button(onClick = {}) { Text("Hi") }
            }
        """.trimIndent()
        val (fixed, count) = AcsKotlinJavaCompletion.addMissingImports(raw)
        assertTrue(count >= 3)
        assertTrue(fixed.contains("import androidx.compose.runtime.Composable"))
        assertTrue(fixed.contains("import androidx.compose.material3.Button"))
        assertTrue(fixed.contains("import androidx.compose.material3.Text"))
    }
}
