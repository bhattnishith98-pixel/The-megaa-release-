package com.tom.rv2ide.studio

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula

data class AcsEditorThemeColors(
    val editorBackgroundArgb: Int,
    val gutterBackgroundArgb: Int,
    val accentArgb: Int,
    val textPrimaryArgb: Int,
    val lineNumberArgb: Int
)

class AcsSoraLanguage(
    private val languageHint: String
) : EmptyLanguage() {

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
        val text = content.getLine(line).take(column).trimEnd()
        return if (text.endsWith("{") || text.endsWith(":") || text.endsWith("(")) 4 else 0
    }

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        val lineStr = content.getLine(position.line)
        val col = position.column.coerceIn(0, lineStr.length)
        var start = col
        while (start > 0 && (lineStr[start - 1].isLetterOrDigit() || lineStr[start - 1] in charArrayOf('_', '@', '.'))) {
            start--
        }
        val prefix = lineStr.substring(start, col)
        val items = AcsKotlinJavaCompletion.completionsForLanguage(languageHint)
        items.forEach { (snippet, desc) ->
            if (prefix.isEmpty() || snippet.contains(prefix, ignoreCase = true)) {
                publisher.addItem(
                    SimpleCompletionItem(snippet, desc, prefix.length, snippet)
                )
            }
        }
    }
}

class AcsSoraEditorHandle(
    val editor: CodeEditor
) {
    private var suppressCallback: Boolean = false
    private var lastSyncedText: String = ""
    private var activeLanguageHint: String = ""
    private var latestOnTextChanged: ((String) -> Unit)? = null

    init {
        editor.subscribeAlways(ContentChangeEvent::class.java) {
            if (suppressCallback) return@subscribeAlways
            val current = editor.text.toString()
            if (current != lastSyncedText) {
                lastSyncedText = current
                latestOnTextChanged?.invoke(current)
            }
        }
    }

    fun bind(
        text: String,
        languageHint: String,
        fontSizeSp: Float,
        wordWrap: Boolean,
        theme: AcsEditorThemeColors,
        onTextChanged: (String) -> Unit
    ) {
        latestOnTextChanged = onTextChanged
        editor.setTextSize(fontSizeSp)
        editor.isWordwrap = wordWrap

        if (activeLanguageHint != languageHint) {
            activeLanguageHint = languageHint
            editor.setEditorLanguage(AcsSoraLanguage(languageHint))
        }

        applyThemeColors(theme)

        if (text != lastSyncedText && editor.text.toString() != text) {
            suppressCallback = true
            try {
                lastSyncedText = text
                editor.setText(text)
            } finally {
                suppressCallback = false
            }
        }
    }

    fun applyThemeColors(theme: AcsEditorThemeColors) {
        val scheme = editor.colorScheme as? SchemeDarcula ?: SchemeDarcula().also { editor.colorScheme = it }
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, theme.editorBackgroundArgb)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, theme.gutterBackgroundArgb)
        scheme.setColor(EditorColorScheme.LINE_NUMBER, theme.lineNumberArgb)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, theme.accentArgb)
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, theme.textPrimaryArgb)
        scheme.setColor(EditorColorScheme.SELECTION_INSERT, theme.accentArgb)
        scheme.setColor(EditorColorScheme.CURRENT_LINE, (theme.gutterBackgroundArgb and 0x00FFFFFF) or 0x55000000)
    }

    fun insertAtCursor(snippet: String) {
        runCatching {
            val cursor = editor.cursor
            editor.text.insert(cursor.leftLine, cursor.leftColumn, snippet)
        }
    }

    fun undo() {
        if (editor.canUndo()) editor.undo()
    }

    fun redo() {
        if (editor.canRedo()) editor.redo()
    }
}

object AcsSoraEditorFactory {
    fun create(context: Context): AcsSoraEditorHandle {
        val editor = CodeEditor(context).apply {
            typefaceText = Typeface.MONOSPACE
            typefaceLineNumber = Typeface.MONOSPACE
            isLineNumberEnabled = true
            setPinLineNumber(true)
            isScalable = true
            setColorScheme(SchemeDarcula())
            setTabWidth(4)
        }
        return AcsSoraEditorHandle(editor)
    }
}
