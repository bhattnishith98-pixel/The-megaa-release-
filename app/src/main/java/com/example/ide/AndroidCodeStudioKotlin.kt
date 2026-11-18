package com.example.ide

import com.tom.rv2ide.studio.AcsKotlinJavaCompletion
import com.tom.rv2ide.studio.AcsResourceIndexer
import com.tom.rv2ide.studio.AcsResourceTable
import com.tom.rv2ide.studio.AcsSourceFile
import com.tom.rv2ide.studio.AcsXmlLayoutInflater

/**
 * Delegating bridge backed by the compiled `:android-code-studio` Android Library AAR module
 * (`com.tom.rv2ide.studio`) and Sora Editor AAR (`io.github.rosemoe:editor:0.24.4`).
 */
object AndroidCodeStudioKotlin {

    data class ResourceTable(
        val strings: Map<String, String>,
        val colors: Map<String, String>,
        val layouts: List<String>,
        val drawables: List<String>
    ) {
        fun resolveValue(raw: String): String =
            AcsResourceTable(strings, colors, layouts, drawables).resolveValue(raw)

        val totalSymbols: Int get() = strings.size + colors.size + layouts.size + drawables.size
    }

    data class InflatedXmlWidget(
        val id: String,
        val tagName: String,
        val kind: String,
        val label: String,
        val orientation: String = "vertical"
    )

    object ResourceIndexer {
        fun indexWorkspace(files: List<IdeFileEntry>): ResourceTable {
            val table = AcsResourceIndexer.indexFiles(
                files.map { AcsSourceFile(path = it.path, title = it.title, content = it.content) }
            )
            return ResourceTable(
                strings = table.strings,
                colors = table.colors,
                layouts = table.layouts,
                drawables = table.drawables
            )
        }
    }

    object XmlInflaterEngine {
        fun inflateXmlLayout(xmlContent: String, resources: ResourceTable): List<InflatedXmlWidget> {
            val nativeTable = AcsResourceTable(
                strings = resources.strings,
                colors = resources.colors,
                layouts = resources.layouts,
                drawables = resources.drawables
            )
            return AcsXmlLayoutInflater.inflateXmlLayout(xmlContent, nativeTable).map { w ->
                InflatedXmlWidget(
                    id = w.id,
                    tagName = w.tagName,
                    kind = w.kind,
                    label = w.label,
                    orientation = w.orientation
                )
            }
        }
    }

    object KotlinJavaCompletion {
        fun detectMissingImports(kotlinOrJavaCode: String): List<String> =
            AcsKotlinJavaCompletion.detectMissingImports(kotlinOrJavaCode)

        fun addMissingImports(kotlinOrJavaCode: String): Pair<String, Int> =
            AcsKotlinJavaCompletion.addMissingImports(kotlinOrJavaCode)
    }
}
