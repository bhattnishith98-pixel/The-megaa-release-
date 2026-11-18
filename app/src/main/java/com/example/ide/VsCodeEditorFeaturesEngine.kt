package com.example.ide

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

data class IdeSnippet(
    val label: String,
    val insertText: String
)

object VsCodeEditorFeaturesEngine {

    private val keywordRegex = Regex(
        """\b(func|def|fun|fn|function|class|interface|object|enum|struct|val|var|let|const|if|else|elif|for|while|in|return|import|package|extends|public|private|protected|override|suspend|true|false|null|None|void|pass|break|continue|signal|export|onready|async|await|match|when)\b"""
    )
    private val typeRegex = Regex(
        """\b(Node2D|CharacterBody2D|Sprite2D|CollisionShape2D|Camera2D|Label|Timer|TouchZone|Vector2|String|Int|Float|Double|Boolean|Unit|Modifier|Column|Row|Text|Button|OutlinedTextField|Switch|Card|Scaffold)\b"""
    )
    private val annotationRegex = Regex("""@[A-Za-z_][A-Za-z0-9_]*""")
    private val numberRegex = Regex("""\b\d+(\.\d+)?[fFlL]?\b""")
    private val stringRegex = Regex(""""([^"\\]|\\.)*"|'([^'\\]|\\.)*'""")
    private val commentRegex = Regex("""(//[^\n]*|#[^\n]*)""")
    private val xmlTagRegex = Regex("""</?[A-Za-z0-9_.:-]+|/?\s*>""")

    /**
     * VS Code / Monaco Syntax Highlighter preserving exact 1-to-1 offsets for Compose BasicTextField.
     */
    fun buildSyntaxVisualTransformation(
        theme: IdeThemePreset,
        languageHint: String
    ): VisualTransformation = VisualTransformation { text ->
        TransformedText(highlightCode(text.text, theme, languageHint), OffsetMapping.Identity)
    }

    fun highlightCode(
        code: String,
        theme: IdeThemePreset,
        languageHint: String
    ): AnnotatedString {
        if (code.length > 24_000) {
            return AnnotatedString(code)
        }
        val kwColor = Color(theme.keywordHex)
        val strColor = Color(theme.stringHex)
        val accentColor = Color(theme.accentHex)
        val commentColor = Color(0xFF6A9955)
        val numberColor = Color(0xFFB5CEA8)
        val typeColor = Color(0xFF4EC9B0)

        return buildAnnotatedString {
            append(code)

            if (languageHint.equals("xml", ignoreCase = true) || code.trimStart().startsWith("<")) {
                xmlTagRegex.findAll(code).forEach { m ->
                    addStyle(SpanStyle(color = kwColor, fontWeight = FontWeight.SemiBold), m.range.first, m.range.last + 1)
                }
            } else {
                keywordRegex.findAll(code).forEach { m ->
                    addStyle(SpanStyle(color = kwColor, fontWeight = FontWeight.SemiBold), m.range.first, m.range.last + 1)
                }
                typeRegex.findAll(code).forEach { m ->
                    addStyle(SpanStyle(color = typeColor), m.range.first, m.range.last + 1)
                }
                annotationRegex.findAll(code).forEach { m ->
                    addStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.Medium), m.range.first, m.range.last + 1)
                }
                numberRegex.findAll(code).forEach { m ->
                    addStyle(SpanStyle(color = numberColor), m.range.first, m.range.last + 1)
                }
            }

            // Strings & comments applied last so they win inside quotes/comments
            stringRegex.findAll(code).forEach { m ->
                addStyle(SpanStyle(color = strColor), m.range.first, m.range.last + 1)
            }
            commentRegex.findAll(code).forEach { m ->
                addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), m.range.first, m.range.last + 1)
            }
        }
    }

    fun snippetsForFile(path: String): List<IdeSnippet> {
        val p = path.lowercase()
        return when {
            p.endsWith(".gd") -> listOf(
                IdeSnippet("func _ready", "func _ready():\n    pass\n"),
                IdeSnippet("func _process", "func _process(delta):\n    move_and_slide()\n"),
                IdeSnippet("spawn", "godot.spawn(\"Sprite2D\", \"Enemy\", 200, 140)"),
                IdeSnippet("voice", "voice_speak(\"Action triggered\")")
            )
            p.endsWith(".py") -> listOf(
                IdeSnippet("def _process", "def _process(delta):\n    move(2.5, 0.0)\n"),
                IdeSnippet("set_prop", "set_prop(\"HudTitle\", \"label\", \"Updated\")"),
                IdeSnippet("voice", "voice_speak(\"Python active\")")
            )
            p.endsWith(".stl") -> listOf(
                IdeSnippet("def main", "def main():\n    godot.set_prop(\"Player\", \"vx\", \"3.0\")\n"),
                IdeSnippet("acs.validate", "let ok = acs.validate()\nlog.info(\"ACS=\" + ok)"),
                IdeSnippet("voice.speak", "voice.speak(\"Automation Studio ready\")")
            )
            p.endsWith(".kt") -> listOf(
                IdeSnippet("@Composable", "@Composable\nfun CustomWidget() {\n    Text(\"Hello\")\n}\n"),
                IdeSnippet("Button", "Button(onClick = { count++ }) { Text(\"Tap\") }"),
                IdeSnippet("OutlinedField", "OutlinedTextField(value = \"\", onValueChange = {}, label = { Text(\"Label\") })")
            )
            p.endsWith(".xml") -> listOf(
                IdeSnippet("<Button>", """<Button android:text="Click Me" />"""),
                IdeSnippet("<TextView>", """<TextView android:text="Title" />"""),
                IdeSnippet("<EditText>", """<EditText android:hint="Type here" />""")
            )
            else -> listOf(
                IdeSnippet("if", "if (cond) {\n    \n}"),
                IdeSnippet("for", "for (i in 0..5) {\n    \n}")
            )
        }
    }

    fun languageDisplayName(path: String): String {
        val p = path.lowercase()
        return when {
            p.endsWith(".gd") -> "GDScript"
            p.endsWith(".py") -> "Python"
            p.endsWith(".stl") -> "StudioLang"
            p.endsWith(".kt") -> "Kotlin"
            p.endsWith(".kts") -> "Kotlin DSL"
            p.endsWith(".java") -> "Java"
            p.endsWith(".ts") || p.endsWith(".tsx") -> "TypeScript"
            p.endsWith(".js") || p.endsWith(".jsx") -> "JavaScript"
            p.endsWith(".cpp") || p.endsWith(".h") -> "C / C++"
            p.endsWith(".xml") -> "Android XML"
            p.endsWith(".tscn") -> "Godot Scene"
            p.endsWith(".json") -> "JSON"
            else -> "Plain Text"
        }
    }

    fun computeCursorLineCol(text: String, cursorOffset: Int): Pair<Int, Int> {
        val clamped = cursorOffset.coerceIn(0, text.length)
        var line = 1
        var col = 1
        for (i in 0 until clamped) {
            if (text[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        return line to col
    }

    fun lineStartOffset(text: String, line1Based: Int): Int {
        if (line1Based <= 1) return 0
        var cur = 1
        for (i in text.indices) {
            if (text[i] == '\n') {
                cur++
                if (cur == line1Based) return (i + 1).coerceAtMost(text.length)
            }
        }
        return text.length
    }
}
