package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.*

data class AiWebPortal(
    val id: String,
    val label: String,
    val url: String
)

object AiStudioWebPortals {
    val presets = listOf(
        AiWebPortal("aistudio", "Google AI Studio", "https://aistudio.google.com"),
        AiWebPortal("anthropic", "Anthropic Claude", "https://console.anthropic.com"),
        AiWebPortal("openai", "OpenAI Platform", "https://platform.openai.com"),
        AiWebPortal("openrouter", "OpenRouter", "https://openrouter.ai"),
        AiWebPortal("groq", "Groq Console", "https://console.groq.com"),
        AiWebPortal("huggingface", "Hugging Face", "https://huggingface.co")
    )

    fun normalizePortalUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return presets.first().url
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://localhost", ignoreCase = true) ||
                trimmed.startsWith("http://127.0.0.1", ignoreCase = true) ||
                trimmed.startsWith("http://10.0.2.2", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.removePrefix("http://")
            trimmed.contains(".") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=" + Uri.encode(trimmed)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AiStudioWebPane(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentUrl by rememberSaveable { mutableStateOf(AiStudioWebPortals.presets.first().url) }
    var urlInput by rememberSaveable { mutableStateOf(currentUrl) }
    var desktopMode by rememberSaveable { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("Google AI Studio") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webError by remember { mutableStateOf<String?>(null) }

    fun applyUserAgent(wv: WebView, desktop: Boolean) {
        wv.settings.userAgentString = if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .testTag("ai_studio_web_pane"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Quick AI Portal Bookmarks + Desktop View toggle
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AiStudioWebPortals.presets, key = { it.id }) { portal ->
                FilterChip(
                    selected = currentUrl.startsWith(portal.url),
                    onClick = {
                        webError = null
                        currentUrl = portal.url
                        urlInput = portal.url
                        webViewRef?.loadUrl(portal.url)
                    },
                    label = { Text(portal.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("ai_portal_${portal.id}")
                )
            }
            item {
                FilterChip(
                    selected = desktopMode,
                    onClick = {
                        val next = !desktopMode
                        desktopMode = next
                        webViewRef?.let { wv ->
                            applyUserAgent(wv, next)
                            wv.reload()
                        }
                    },
                    label = { Text(if (desktopMode) "Desktop ON" else "Mobile View", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("ai_web_desktop_toggle")
                )
            }
        }

        // URL Navigation Bar + External Browser Fallback
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { webViewRef?.goBack() },
                modifier = Modifier.size(40.dp).testTag("ai_web_back_btn")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = CyberCyan)
            }
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                singleLine = true,
                placeholder = { Text("https://aistudio.google.com") },
                modifier = Modifier.weight(1f).testTag("ai_web_url_input")
            )
            Button(
                onClick = {
                    webError = null
                    val target = AiStudioWebPortals.normalizePortalUrl(urlInput)
                    urlInput = target
                    currentUrl = target
                    webViewRef?.loadUrl(target)
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.defaultMinSize(minHeight = 44.dp).testTag("ai_web_go_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianBg)
            ) {
                Text("Go", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(
                onClick = {
                    webError = null
                    webViewRef?.reload()
                },
                modifier = Modifier.size(40.dp).testTag("ai_web_reload_btn")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = EmeraldRun)
            }
            IconButton(
                onClick = {
                    val target = AiStudioWebPortals.normalizePortalUrl(currentUrl)
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.size(40.dp).testTag("ai_web_open_external_btn")
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in External Browser", tint = AmberWarn)
            }
        }

        if (loadProgress in 1..99) {
            LinearProgressIndicator(
                progress = { loadProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            pageTitle.ifBlank { currentUrl },
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (webError != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier.fillMaxWidth().border(1.dp, AmberWarn.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            ) {
                Text(
                    "WebView note: $webError — If Google/Anthropic blocks embedded OAuth sign-in, tap the Open Browser icon above.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberWarn,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // Embedded HTTPS WebView for Google AI Studio & AI Consoles
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                .background(SlateSurface, RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        isNestedScrollingEnabled = true
                        runCatching {
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.loadsImagesAutomatically = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.setSupportMultipleWindows(false)
                        applyUserAgent(this, desktopMode)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val u = request?.url?.toString().orEmpty()
                                return !(u.startsWith("https://") || u.startsWith("http://localhost") || u.startsWith("http://127.0.0.1"))
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                    urlInput = url
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    webError = error?.description?.toString() ?: "Page failed to load"
                                }
                            }
                        }
                        webViewRef = this
                        runCatching { loadUrl(currentUrl) }.onFailure { webError = it.message }
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("ai_studio_webview")
            )
        }
    }
}
