package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.material.icons.filled.Key
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
import androidx.core.content.ContextCompat
import com.example.ui.theme.*

data class AiWebPortal(
    val id: String,
    val label: String,
    val url: String
)

object AiStudioWebPortals {
    val presets = listOf(
        AiWebPortal("aistudio", "Google AI Studio", "https://aistudio.google.com"),
        AiWebPortal("aistudio_apikey", "Get Gemini Key", "https://aistudio.google.com/apikey"),
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

    fun isGoogleOAuthUrl(url: String): Boolean =
        url.contains("accounts.google.com", ignoreCase = true) ||
            url.contains("disallowed_useragent", ignoreCase = true)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AiStudioWebPane(
    modifier: Modifier = Modifier,
    onOpenAgentKeySetup: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var currentUrl by rememberSaveable { mutableStateOf(AiStudioWebPortals.presets.first().url) }
    var urlInput by rememberSaveable { mutableStateOf(currentUrl) }
    var desktopMode by rememberSaveable { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("Google AI Studio") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webError by remember { mutableStateOf<String?>(null) }
    val isOAuthGate = AiStudioWebPortals.isGoogleOAuthUrl(currentUrl) ||
        pageTitle.contains("403", ignoreCase = true) ||
        pageTitle.contains("Sign in", ignoreCase = true)

    fun applyUserAgent(wv: WebView, desktop: Boolean) {
        wv.settings.userAgentString = if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }
    }

    fun openExternalUrl(targetUrl: String) {
        val target = AiStudioWebPortals.normalizePortalUrl(targetUrl)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            webError = "No browser app found to open $target"
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
                onClick = {
                    if (webViewRef?.canGoBack() == true) webViewRef?.goBack()
                },
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
                onClick = { openExternalUrl(currentUrl) },
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

        if (webError != null || isOAuthGate) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AmberWarn.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        webError ?: "Google Sign-In blocks embedded WebViews (disallowed_useragent). Open AI Studio in your browser to sign in or copy your Gemini API key, then paste it in Agent Config.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberWarn
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { openExternalUrl(currentUrl) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AmberWarn, contentColor = ObsidianBg),
                            modifier = Modifier.testTag("ai_web_banner_open_browser_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open in Browser", style = MaterialTheme.typography.labelSmall)
                        }
                        if (onOpenAgentKeySetup != null) {
                            OutlinedButton(
                                onClick = onOpenAgentKeySetup,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("ai_web_banner_setup_key_btn")
                            ) {
                                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(14.dp), tint = CyberCyan)
                                Spacer(Modifier.width(4.dp))
                                Text("Paste API Key", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                            }
                        }
                    }
                }
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
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.loadsImagesAutomatically = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.setSupportMultipleWindows(true)
                        applyUserAgent(this, desktopMode)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }

                            override fun onPermissionRequest(request: PermissionRequest?) {
                                val req = request ?: return
                                val allowed = req.resources.filter { res ->
                                    when (res) {
                                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> true
                                        else -> false
                                    }
                                }.toTypedArray()
                                if (allowed.isNotEmpty()) req.grant(allowed) else req.deny()
                            }

                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?
                            ): Boolean {
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                val popupWebView = WebView(ctx).apply {
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                                            val target = request?.url?.toString().orEmpty()
                                            if (target.startsWith("https://")) {
                                                if (AiStudioWebPortals.isGoogleOAuthUrl(target)) {
                                                    openExternalUrl(target)
                                                } else {
                                                    webViewRef?.loadUrl(target)
                                                }
                                            }
                                            v?.destroy()
                                            return true
                                        }
                                    }
                                }
                                transport.webView = popupWebView
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val u = request?.url?.toString().orEmpty()
                                if (u.startsWith("https://") || u.startsWith("http://localhost") || u.startsWith("http://127.0.0.1")) {
                                    if (AiStudioWebPortals.isGoogleOAuthUrl(u)) {
                                        currentUrl = u
                                        urlInput = u
                                    }
                                    return false
                                }
                                if (u.startsWith("http://")) {
                                    view?.loadUrl("https://" + u.removePrefix("http://"))
                                    return true
                                }
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                    urlInput = url
                                }
                                runCatching { CookieManager.getInstance().flush() }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                                    webError = "HTTP ${errorResponse?.statusCode}: ${errorResponse?.reasonPhrase ?: "Request blocked"}"
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
