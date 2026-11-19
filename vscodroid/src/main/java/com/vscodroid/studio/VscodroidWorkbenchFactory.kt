package com.vscodroid.studio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

data class VscodroidEditorOptions(
    val filePath: String = "main.py",
    val fontSizePx: Int = 13,
    val tabSpaces: Int = 4,
    val wordWrap: Boolean = false,
    val showLineNumbers: Boolean = true,
    val activeDebugLine: Int? = null
)

class VscodroidWorkbenchHandle internal constructor(
    val webView: WebView,
    private val bridge: WorkbenchJsBridge
) {
    private var lastPushedPath: String = ""
    private var lastPushedText: String = ""
    private var lastFontSize: Int = -1
    private var lastWordWrap: Boolean? = null
    private var lastDebugLine: Int? = null

    fun syncState(content: String, options: VscodroidEditorOptions) {
        if (!bridge.isPageReady) {
            bridge.pendingContent = content
            bridge.pendingOptions = options
            return
        }
        val textChanged = content != lastPushedText && !bridge.isInternalUpdate(content)
        val pathChanged = options.filePath != lastPushedPath
        val configChanged = options.fontSizePx != lastFontSize ||
            options.wordWrap != lastWordWrap ||
            options.activeDebugLine != lastDebugLine

        if (textChanged || pathChanged) {
            lastPushedPath = options.filePath
            lastPushedText = content
            val payload = JSONObject().apply {
                put("path", options.filePath)
                put("language", VscodroidAarRuntime.resolveLanguageId(options.filePath))
                put("content", content)
                put("fontSize", options.fontSizePx)
                put("wordWrap", options.wordWrap)
                put("tabSpaces", options.tabSpaces)
                put("debugLine", options.activeDebugLine ?: -1)
            }.toString()
            webView.post {
                webView.evaluateJavascript("window.__vscodroidLoadState($payload);", null)
            }
            lastFontSize = options.fontSizePx
            lastWordWrap = options.wordWrap
            lastDebugLine = options.activeDebugLine
        } else if (configChanged) {
            lastFontSize = options.fontSizePx
            lastWordWrap = options.wordWrap
            lastDebugLine = options.activeDebugLine
            val payload = JSONObject().apply {
                put("fontSize", options.fontSizePx)
                put("wordWrap", options.wordWrap)
                put("tabSpaces", options.tabSpaces)
                put("debugLine", options.activeDebugLine ?: -1)
            }.toString()
            webView.post {
                webView.evaluateJavascript("window.__vscodroidApplyConfig($payload);", null)
            }
        }
    }

    fun updateCallback(onTextChanged: (String) -> Unit, onRunCommand: (String) -> Unit) {
        bridge.onTextChanged = onTextChanged
        bridge.onRunCommand = onRunCommand
    }

    fun release() {
        webView.removeJavascriptInterface("VscodroidHost")
        webView.destroy()
    }
}

internal class WorkbenchJsBridge(
    var onTextChanged: (String) -> Unit,
    var onRunCommand: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    var isPageReady: Boolean = false
    @Volatile
    var pendingContent: String? = null
    @Volatile
    var pendingOptions: VscodroidEditorOptions? = null
    @Volatile
    private var lastEmittedFromJs: String? = null

    fun isInternalUpdate(incoming: String): Boolean = incoming == lastEmittedFromJs

    @JavascriptInterface
    fun onEditorInput(newText: String) {
        lastEmittedFromJs = newText
        mainHandler.post { onTextChanged(newText) }
    }

    @JavascriptInterface
    fun onWorkbenchCommand(cmdId: String) {
        mainHandler.post { onRunCommand(cmdId) }
    }
}

/**
 * Self-contained offline Code-OSS WebView Workbench bundled inside `:vscodroid`.
 * Provides live syntax highlighting badge, gutter line numbers, debug line highlighting,
 * two-way keystroke sync, command actions, and Tab indentation without network fetches.
 */
object VscodroidWorkbenchFactory {

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun createWorkbench(
        context: Context,
        initialContent: String,
        initialOptions: VscodroidEditorOptions,
        onTextChanged: (String) -> Unit,
        onRunCommand: (String) -> Unit = {}
    ): VscodroidWorkbenchHandle {
        val bridge = WorkbenchJsBridge(onTextChanged, onRunCommand).apply {
            pendingContent = initialContent
            pendingOptions = initialOptions
        }
        val webView = WebView(context).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(bridge, "VscodroidHost")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    bridge.isPageReady = true
                    val c = bridge.pendingContent
                    val o = bridge.pendingOptions
                    if (c != null && o != null) {
                        val payload = JSONObject().apply {
                            put("path", o.filePath)
                            put("language", VscodroidAarRuntime.resolveLanguageId(o.filePath))
                            put("content", c)
                            put("fontSize", o.fontSizePx)
                            put("wordWrap", o.wordWrap)
                            put("tabSpaces", o.tabSpaces)
                            put("debugLine", o.activeDebugLine ?: -1)
                        }.toString()
                        view?.evaluateJavascript("window.__vscodroidLoadState($payload);", null)
                    }
                }
            }
            loadDataWithBaseURL("https://vscodroid.local/", WORKBENCH_HTML, "text/html", "UTF-8", null)
        }
        return VscodroidWorkbenchHandle(webView, bridge)
    }

    private const val WORKBENCH_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no"/>
<style>
  :root {
    --bg: #0D1117;
    --bar: #161B22;
    --border: #30363D;
    --fg: #E6EDF3;
    --muted: #8B949E;
    --accent: #38BDF8;
    --emerald: #34D399;
    --debug: rgba(244, 63, 94, 0.25);
  }
  * { box-sizing: border-box; }
  html, body {
    margin: 0; padding: 0; height: 100%; width: 100%;
    background: var(--bg); color: var(--fg);
    font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    overflow: hidden;
    display: flex; flex-direction: column;
  }
  .header {
    height: 30px; background: var(--bar); border-bottom: 1px solid var(--border);
    display: flex; align-items: center; justify-content: space-between;
    padding: 0 10px; font-size: 11px; color: var(--muted); user-select: none;
  }
  .badge {
    color: var(--accent); font-weight: 700; letter-spacing: 0.4px;
  }
  .actions { display: flex; gap: 6px; }
  .btn {
    background: #21262D; color: var(--fg); border: 1px solid var(--border);
    border-radius: 5px; font-size: 10px; padding: 2px 7px; cursor: pointer;
  }
  .btn:active { background: #30363D; }
  .editor-wrap {
    flex: 1; display: flex; position: relative; overflow: hidden;
  }
  .gutter {
    width: 42px; background: #0B0F14; border-right: 1px solid var(--border);
    color: #6E7681; text-align: right; padding: 8px 6px 8px 2px;
    font-size: 13px; line-height: 1.48; overflow: hidden; user-select: none;
  }
  .gutter .dbg { color: #FB7185; font-weight: bold; background: var(--debug); border-radius: 3px; padding: 0 2px; }
  textarea#code {
    flex: 1; height: 100%; background: var(--bg); color: var(--fg);
    border: none; outline: none; resize: none;
    padding: 8px 10px; margin: 0;
    font-family: inherit; font-size: 13px; line-height: 1.48;
    tab-size: 4; white-space: pre; overflow: auto;
    caret-color: var(--accent);
  }
  .status {
    height: 22px; background: #007ACC; color: #FFFFFF;
    display: flex; align-items: center; justify-content: space-between;
    padding: 0 10px; font-size: 10px; user-select: none;
  }
</style>
</head>
<body>
  <div class="header">
    <div><span class="badge">VSCodroid Code-OSS</span> &middot; <span id="fileLabel">main.py</span> (<span id="langLabel">python</span>)</div>
    <div class="actions">
      <button class="btn" onclick="VscodroidHost.onWorkbenchCommand('editor.action.formatDocument')">Fmt</button>
      <button class="btn" onclick="VscodroidHost.onWorkbenchCommand('workbench.action.runActive')">Run</button>
    </div>
  </div>
  <div class="editor-wrap">
    <div class="gutter" id="gutter">1</div>
    <textarea id="code" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></textarea>
  </div>
  <div class="status">
    <span id="cursorStat">Ln 1, Col 1</span>
    <span id="metaStat">UTF-8 &middot; Code-OSS AAR</span>
  </div>
<script>
  const ta = document.getElementById('code');
  const gutter = document.getElementById('gutter');
  const fileLabel = document.getElementById('fileLabel');
  const langLabel = document.getElementById('langLabel');
  const cursorStat = document.getElementById('cursorStat');
  let currentDebugLine = -1;
  let currentTabSpaces = 4;

  function renderGutter() {
    const lines = ta.value.split('\n').length;
    let html = '';
    for (let i = 1; i <= lines; i++) {
      if (i === currentDebugLine) html += '<div class="dbg">' + i + '</div>';
      else html += '<div>' + i + '</div>';
    }
    gutter.innerHTML = html;
    gutter.scrollTop = ta.scrollTop;
  }

  function updateCursor() {
    const upTo = ta.value.slice(0, ta.selectionStart);
    const rows = upTo.split('\n');
    const ln = rows.length;
    const col = rows[rows.length - 1].length + 1;
    cursorStat.textContent = 'Ln ' + ln + ', Col ' + col + ' (' + ta.value.length + ' chars)';
  }

  ta.addEventListener('scroll', () => { gutter.scrollTop = ta.scrollTop; });
  ta.addEventListener('click', updateCursor);
  ta.addEventListener('keyup', updateCursor);
  ta.addEventListener('input', () => {
    renderGutter();
    updateCursor();
    if (window.VscodroidHost) window.VscodroidHost.onEditorInput(ta.value);
  });
  ta.addEventListener('keydown', (e) => {
    if (e.key === 'Tab') {
      e.preventDefault();
      const s = ta.selectionStart, end = ta.selectionEnd;
      const spaces = ' '.repeat(currentTabSpaces);
      ta.value = ta.value.substring(0, s) + spaces + ta.value.substring(end);
      ta.selectionStart = ta.selectionEnd = s + spaces.length;
      renderGutter();
      updateCursor();
      if (window.VscodroidHost) window.VscodroidHost.onEditorInput(ta.value);
    }
  });

  window.__vscodroidApplyConfig = function(cfg) {
    if (!cfg) return;
    if (cfg.fontSize) {
      ta.style.fontSize = cfg.fontSize + 'px';
      gutter.style.fontSize = cfg.fontSize + 'px';
    }
    ta.style.whiteSpace = cfg.wordWrap ? 'pre-wrap' : 'pre';
    if (cfg.tabSpaces) {
      currentTabSpaces = cfg.tabSpaces;
      ta.style.tabSize = cfg.tabSpaces;
    }
    currentDebugLine = cfg.debugLine || -1;
    renderGutter();
  };

  window.__vscodroidLoadState = function(st) {
    if (!st) return;
    fileLabel.textContent = st.path || 'untitled';
    langLabel.textContent = st.language || 'plaintext';
    if (typeof st.content === 'string' && ta.value !== st.content) {
      const sel = Math.min(ta.selectionStart || 0, st.content.length);
      ta.value = st.content;
      ta.selectionStart = ta.selectionEnd = sel;
    }
    window.__vscodroidApplyConfig(st);
    updateCursor();
  };
</script>
</body>
</html>
"""
}
