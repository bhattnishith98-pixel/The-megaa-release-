package com.example

import com.example.ide.DetectedProjectType
import com.example.ide.IdeFileEntry
import com.example.ide.IdeProjectDescriptor
import com.example.ide.ProjectSourceKind
import com.example.ide.ProjectTemplateKind
import com.example.ide.UniversalProjectManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class UniversalProjectManagerTest {

    @Test
    fun createAndReloadAllProjectTemplatesAndRegistryRoundTrip() {
        val tempRoot = Files.createTempDirectory("upm_templates_test").toFile()
        try {
            val godotWs = UniversalProjectManager.createNewInAppProject(
                workspaceRoot = tempRoot,
                rawName = "Space Runner",
                template = ProjectTemplateKind.GODOT_2D
            )
            assertEquals(DetectedProjectType.GODOT_4X, godotWs.descriptor.detectedType)
            assertEquals(ProjectSourceKind.IN_APP_PROJECT, godotWs.descriptor.sourceKind)
            assertTrue(godotWs.files.any { it.path == "project.godot" })
            assertTrue(godotWs.files.any { it.path == "scenes/main.tscn" })
            assertTrue(godotWs.files.any { it.path == "scripts/player.gd" })

            val androidWs = UniversalProjectManager.createNewInAppProject(
                workspaceRoot = tempRoot,
                rawName = "Wallet App",
                template = ProjectTemplateKind.ANDROID_COMPOSE
            )
            assertEquals(DetectedProjectType.ANDROID_GRADLE, androidWs.descriptor.detectedType)
            assertTrue(androidWs.files.any { it.path.endsWith("MainActivity.kt") })

            val pyWs = UniversalProjectManager.createNewInAppProject(
                workspaceRoot = tempRoot,
                rawName = "Data Tool",
                template = ProjectTemplateKind.PYTHON_SCRIPT
            )
            assertEquals(DetectedProjectType.PYTHON, pyWs.descriptor.detectedType)
            assertTrue(pyWs.files.any { it.path == "main.py" })

            val cppWs = UniversalProjectManager.createNewInAppProject(
                workspaceRoot = tempRoot,
                rawName = "Native Engine",
                template = ProjectTemplateKind.CPP_NATIVE
            )
            assertEquals(DetectedProjectType.CPP_NATIVE, cppWs.descriptor.detectedType)
            assertTrue(cppWs.files.any { it.path == "src/main.cpp" })

            UniversalProjectManager.saveRegistry(
                tempRoot,
                listOf(godotWs.descriptor, androidWs.descriptor, pyWs.descriptor, cppWs.descriptor)
            )
            val loadedReg = UniversalProjectManager.loadRegistry(tempRoot)
            assertEquals(4, loadedReg.size)

            val reloaded = UniversalProjectManager.loadLocalProjectDir(godotWs.descriptor)
            assertEquals(godotWs.files.size, reloaded.files.size)
            assertEquals(DetectedProjectType.GODOT_4X, reloaded.descriptor.detectedType)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun importZipStripsSingleWrapperFolderRejectsZipSlipAndExportsLiveBuffers() {
        val tempRoot = Files.createTempDirectory("upm_zip_test").toFile()
        try {
            val zipBytes = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos).use { zos ->
                    zos.putNextEntry(ZipEntry("my-godot-game-main/project.godot"))
                    zos.write("config_version=5\n[application]\nconfig/name=\"ZipGame\"\n".toByteArray())
                    zos.closeEntry()

                    zos.putNextEntry(ZipEntry("my-godot-game-main/scripts/enemy.gd"))
                    zos.write("extends Node2D\nfunc _ready():\n\tpass\n".toByteArray())
                    zos.closeEntry()

                    zos.putNextEntry(ZipEntry("my-godot-game-main/build/ignored.txt"))
                    zos.write("ignored build output".toByteArray())
                    zos.closeEntry()
                }
                baos.toByteArray()
            }

            val loaded = UniversalProjectManager.importAnyZipStream(
                workspaceRoot = tempRoot,
                zipDisplayName = "my-godot-game.zip",
                inputStream = ByteArrayInputStream(zipBytes)
            )
            assertEquals(ProjectSourceKind.ZIP_IMPORT, loaded.descriptor.sourceKind)
            assertEquals(DetectedProjectType.GODOT_4X, loaded.descriptor.detectedType)
            assertTrue(loaded.files.any { it.path == "project.godot" })
            assertTrue(loaded.files.any { it.path == "scripts/enemy.gd" })
            assertFalse(loaded.files.any { it.path.contains("my-godot-game-main/") })
            assertFalse(loaded.files.any { it.path.contains("build/") })

            val modifiedBuffer = loaded.files.map { entry ->
                if (entry.path == "scripts/enemy.gd") {
                    entry.copy(content = "extends Node2D\n# Live edited buffer\n")
                } else {
                    entry
                }
            } + IdeFileEntry(
                path = "notes/todo.md",
                title = "todo.md",
                content = "# Notes\nEdited in IDE\n",
                languageHint = "markdown"
            )

            val outZip = File(tempRoot, "exported_live.zip")
            UniversalProjectManager.exportProjectToZip(loaded.descriptor, modifiedBuffer, outZip)
            assertTrue(outZip.isFile && outZip.length() > 0L)
            ZipFile(outZip).use { zf ->
                val enemyText = zf.getInputStream(zf.getEntry("scripts/enemy.gd")).bufferedReader().readText()
                assertTrue(enemyText.contains("Live edited buffer"))
                assertTrue(zf.getEntry("notes/todo.md") != null)
            }

            // Verify Zip-Slip is strictly rejected
            val evilZip = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos).use { zos ->
                    zos.putNextEntry(ZipEntry("../../etc/passwd"))
                    zos.write("evil".toByteArray())
                    zos.closeEntry()
                }
                baos.toByteArray()
            }
            try {
                UniversalProjectManager.importAnyZipStream(
                    workspaceRoot = tempRoot,
                    zipDisplayName = "evil.zip",
                    inputStream = ByteArrayInputStream(evilZip)
                )
                fail("Expected SecurityException for Zip-Slip path")
            } catch (_: SecurityException) {
                // Expected
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun parseGithubArchiveCandidatesHandlesShorthandBranchesTagsAndDirectZip() {
        val shortSpec = UniversalProjectManager.parseGithubArchiveCandidates("octocat/hello-world")
        assertEquals("hello-world", shortSpec.projectName)
        assertEquals("https://github.com/octocat/hello-world", shortSpec.canonicalRepoUrl)
        assertTrue(shortSpec.archiveUrls.any { it.endsWith("/refs/heads/main.zip") })
        assertTrue(shortSpec.archiveUrls.any { it.endsWith("/refs/heads/master.zip") })

        val tagSpec = UniversalProjectManager.parseGithubArchiveCandidates(
            "https://github.com/godotengine/godot/releases/tag/4.7.2-stable"
        )
        assertEquals("godot", tagSpec.projectName)
        assertEquals(
            "https://github.com/godotengine/godot/archive/refs/tags/4.7.2-stable.zip",
            tagSpec.archiveUrls.first()
        )

        val treeSpec = UniversalProjectManager.parseGithubArchiveCandidates(
            "https://github.com/org/repo/tree/feature/live-ui"
        )
        assertTrue(
            treeSpec.archiveUrls.first().contains("/refs/heads/feature/live-ui.zip")
        )

        val zipSpec = UniversalProjectManager.parseGithubArchiveCandidates(
            "https://example.com/archives/sample.zip"
        )
        assertEquals(listOf("https://example.com/archives/sample.zip"), zipSpec.archiveUrls)
    }

    @Test
    fun sanitizeRelativePathAndDetectProjectTypeValidateInputs() {
        assertEquals("scripts/player.gd", UniversalProjectManager.sanitizeRelativePath("/scripts\\player.gd"))
        try {
            UniversalProjectManager.sanitizeRelativePath("../secret.txt")
            fail("Expected IllegalArgumentException for parent traversal")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        assertEquals(
            DetectedProjectType.GODOT_4X,
            UniversalProjectManager.detectProjectType(listOf("project.godot", "main.gd"))
        )
        assertEquals(
            DetectedProjectType.ANDROID_GRADLE,
            UniversalProjectManager.detectProjectType(listOf("app/build.gradle.kts", "MainActivity.kt"))
        )
        assertEquals(
            DetectedProjectType.WEB_JS,
            UniversalProjectManager.detectProjectType(listOf("package.json", "src/App.tsx"))
        )
        assertEquals(
            DetectedProjectType.PYTHON,
            UniversalProjectManager.detectProjectType(listOf("main.py", "requirements.txt"))
        )
        assertEquals(
            DetectedProjectType.CPP_NATIVE,
            UniversalProjectManager.detectProjectType(listOf("CMakeLists.txt", "src/main.cpp"))
        )
    }

    @Test
    fun writeAndDeleteLocalFileRespectsProjectBoundary() {
        val tempRoot = Files.createTempDirectory("upm_local_rw").toFile()
        try {
            val desc = IdeProjectDescriptor(
                id = "test_local",
                name = "Test Local",
                sourceKind = ProjectSourceKind.IN_APP_PROJECT,
                detectedType = DetectedProjectType.GENERAL,
                localDirPath = tempRoot.absolutePath
            )
            val written = UniversalProjectManager.writeLocalFile(File(desc.localDirPath), "nested/hello.txt", "abc")
            assertTrue(written.isFile)
            assertEquals("abc", written.readText())
            assertTrue(UniversalProjectManager.deleteLocalFile(File(desc.localDirPath), "nested/hello.txt"))
            assertFalse(written.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
