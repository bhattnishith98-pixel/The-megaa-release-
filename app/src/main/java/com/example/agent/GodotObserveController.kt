package com.example.agent

import android.graphics.Bitmap
import com.example.services.ScreenCaptureService
import com.example.services.StudioAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ObserveStepLog(
    val stepIndex: Int,
    val phase: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Universal foreground Android app screen observation automation loop:
 * OBSERVE (any foreground app) -> ANALYZE -> SPEAK PLAN FIRST -> ACT -> VERIFY -> REPEAT.
 * Uses MediaProjection (ScreenCaptureService) + AccessibilityService (StudioAccessibilityService)
 * + TTS voice announcement before executing actions, with Emergency Stop.
 */
class GodotObserveController(
    private val aiProvider: AgentAiProvider
) {
    private val _status = MutableStateFlow(AgentStatus.IDLE)
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private val _stepLogs = MutableStateFlow<List<ObserveStepLog>>(emptyList())
    val stepLogs: StateFlow<List<ObserveStepLog>> = _stepLogs.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<GodotStepDecision?>(null)
    val pendingConfirmation: StateFlow<GodotStepDecision?> = _pendingConfirmation.asStateFlow()

    private val _lastObservedFrame = MutableStateFlow<Bitmap?>(null)
    val lastObservedFrame: StateFlow<Bitmap?> = _lastObservedFrame.asStateFlow()

    private val _foregroundPackage = MutableStateFlow("")
    val foregroundPackage: StateFlow<String> = _foregroundPackage.asStateFlow()

    private val _lastSpokenPlan = MutableStateFlow("")
    val lastSpokenPlan: StateFlow<String> = _lastSpokenPlan.asStateFlow()

    private var loopJob: Job? = null
    private var paused = false
    private var confirmationGranted: Boolean? = null

    fun start(
        scope: CoroutineScope,
        goal: String,
        maxSteps: Int = 12,
        speakBeforeAct: (suspend (String) -> Unit)? = null
    ) {
        stop()
        paused = false
        _stepLogs.value = emptyList()
        loopJob = scope.launch {
            var step = 1
            try {
                speakBeforeAct?.invoke("Starting automation on foreground app. Goal: $goal")
                while (isActive && step <= maxSteps) {
                    while (paused && isActive) {
                        _status.value = AgentStatus.PAUSED
                        delay(200)
                    }

                    // 1. OBSERVE ANY FOREGROUND APP
                    _status.value = AgentStatus.OBSERVING
                    val captureSvc = ScreenCaptureService.instance
                    val accSvc = StudioAccessibilityService.instance
                    val fgPkg = StudioAccessibilityService.foregroundPackage.value
                    _foregroundPackage.value = fgPkg
                    if (captureSvc == null && accSvc == null) {
                        appendLog(step, "OBSERVE", "Waiting: enable Accessibility Service (recommended) or MediaProjection Screen Capture first.")
                        _status.value = AgentStatus.ERROR
                        break
                    }
                    val frame = captureSvc?.captureLatestBitmap()
                        ?: ScreenCaptureService.lastCapturedBitmap.value
                        ?: accSvc?.captureScreenViaAccessibility()
                    if (frame != null) _lastObservedFrame.value = frame
                    val uiElements = accSvc?.dumpVisibleElements().orEmpty()
                    val uiSummary = uiElements.joinToString("\n") {
                        "[${it.text.ifBlank { it.contentDescription }}] (${it.bounds.centerX()},${it.bounds.centerY()}) click=${it.clickable}"
                    }
                    appendLog(step, "OBSERVE", "App=${fgPkg.ifBlank { "foreground" }} frame=${frame != null}, UI nodes=${uiElements.size}")

                    // 2. ANALYZE & PLAN
                    _status.value = AgentStatus.PLANNING
                    val decision = aiProvider.analyzeObservedScreen(goal, frame, uiSummary, fgPkg)
                    val announcement = decision.spokenAnnouncement.ifBlank {
                        "I am going to ${decision.actionKind}: ${decision.reason}"
                    }
                    _lastSpokenPlan.value = announcement
                    appendLog(step, "PLAN", "${decision.observation} -> ${decision.actionKind}: ${decision.reason}")

                    // 3. SPEAK FIRST BEFORE ACTING
                    if (speakBeforeAct != null) {
                        appendLog(step, "SPEAK", announcement)
                        speakBeforeAct.invoke(announcement)
                    }

                    if (decision.actionKind == "complete") {
                        appendLog(step, "VERIFY", "Goal completed.")
                        _status.value = AgentStatus.IDLE
                        break
                    }

                    if (decision.isDestructive || decision.actionKind == "confirm_needed") {
                        _status.value = AgentStatus.WAITING_APPROVAL
                        confirmationGranted = null
                        _pendingConfirmation.value = decision
                        while (confirmationGranted == null && isActive) delay(150)
                        _pendingConfirmation.value = null
                        if (confirmationGranted != true) {
                            appendLog(step, "ACT", "Action cancelled by user confirmation.")
                            _status.value = AgentStatus.IDLE
                            break
                        }
                    }

                    // 4. ACT
                    _status.value = AgentStatus.ACTING
                    val acted = when (decision.actionKind) {
                        "tap" -> accSvc?.performTap(decision.x, decision.y) ?: false
                        "type" -> accSvc?.performTypeText(decision.textToType) ?: false
                        "scroll" -> accSvc?.performSwipe(540f, 1200f, 540f, 700f) ?: false
                        "back" -> accSvc?.goBack() ?: false
                        else -> true
                    }
                    appendLog(step, "ACT", "Executed ${decision.actionKind} -> success=$acted")

                    // 5. VERIFY
                    _status.value = AgentStatus.VERIFYING
                    delay(800)
                    step++
                }
                if (_status.value != AgentStatus.ERROR) _status.value = AgentStatus.IDLE
            } catch (e: CancellationException) {
                _status.value = AgentStatus.IDLE
                throw e
            } catch (e: Exception) {
                appendLog(step, "ERROR", e.localizedMessage ?: "Observation error")
                _status.value = AgentStatus.ERROR
            }
        }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }
    fun confirmPending(approved: Boolean) { confirmationGranted = approved }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        paused = false
        _pendingConfirmation.value = null
        _status.value = AgentStatus.IDLE
    }

    private fun appendLog(step: Int, phase: String, msg: String) {
        _stepLogs.value = _stepLogs.value + ObserveStepLog(step, phase, msg)
    }
}
