package com.example.monetization

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MonetizationState(
    val isForeground: Boolean = false,
    val isSubscribed: Boolean = false,
    val subscriptionExpiresAtMs: Long = 0L,
    val foregroundSecondsWithoutAd: Long = 0L,
    val secondsUntilNextRewardedPrompt: Long = MonetizationManager.REWARDED_AD_CADENCE_SECONDS,
    val showPeriodicAdPrompt: Boolean = false,
    val showMandatoryAdGate: Boolean = false,
    val isPlayingTestAd: Boolean = false,
    val testAdSecondsRemaining: Int = 0,
    val totalTestAdsWatched: Int = 0,
    val adUnitId: String = MonetizationManager.TEST_REWARDED_AD_UNIT_ID,
    val profile: MonetizationProfile = MonetizationProfile.defaultTestProfile(),
    val profileWarnings: List<String> = emptyList()
) {
    val foregroundSecondsRemainingBeforeGate: Long
        get() = (profile.effectiveGateSeconds() - foregroundSecondsWithoutAd).coerceAtLeast(0L)
}

/**
 * Manages Google Mobile Ads Rewarded Ads (TEST by default), ₹300/month ad-free subscription,
 * foreground-only 20-minute "This is Ad Time" gate, and editable Ad/UPI JSON profiles.
 */
class MonetizationManager(
    context: Context,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var tickerJob: Job? = null
    private var adPlayerJob: Job? = null

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<MonetizationState> = _state.asStateFlow()

    private fun loadInitialState(): MonetizationState {
        val now = clockMs()
        val rawProfile = prefs.getString(KEY_PROFILE_JSON, null)
        val profile = if (!rawProfile.isNullOrBlank()) {
            MonetizationProfile.fromJson(rawProfile).getOrElse { MonetizationProfile.defaultTestProfile() }
        } else {
            MonetizationProfile.defaultTestProfile()
        }
        val exp = prefs.getLong(KEY_SUB_EXPIRES_MS, 0L)
        val subActive = exp > now
        val noAdSecs = prefs.getLong(KEY_NO_AD_SECS, 0L).coerceAtLeast(0L)
        val watched = prefs.getInt(KEY_ADS_WATCHED, 0)
        return MonetizationState(
            isForeground = false,
            isSubscribed = subActive,
            subscriptionExpiresAtMs = if (subActive) exp else 0L,
            foregroundSecondsWithoutAd = if (subActive) 0L else noAdSecs,
            secondsUntilNextRewardedPrompt = profile.effectiveCadenceSeconds(),
            showMandatoryAdGate = !subActive && noAdSecs >= profile.effectiveGateSeconds(),
            totalTestAdsWatched = watched,
            adUnitId = profile.effectiveRewardedAdUnitId(),
            profile = profile,
            profileWarnings = profile.validate()
        )
    }

    fun getProfileJson(): String = _state.value.profile.toPrettyJson()

    fun updateProfileFromJson(rawJson: String): Result<MonetizationProfile> {
        val parsed = MonetizationProfile.fromJson(rawJson)
        parsed.onSuccess { prof ->
            saveAndApplyProfile(prof)
        }
        return parsed
    }

    fun updateProfile(profile: MonetizationProfile) {
        saveAndApplyProfile(profile)
    }

    fun resetProfileToTestDefaults(): MonetizationProfile {
        val def = MonetizationProfile.defaultTestProfile()
        saveAndApplyProfile(def)
        return def
    }

    private fun saveAndApplyProfile(profile: MonetizationProfile) {
        prefs.edit().putString(KEY_PROFILE_JSON, profile.toPrettyJson()).apply()
        val cur = _state.value
        _state.value = cur.copy(
            profile = profile,
            adUnitId = profile.effectiveRewardedAdUnitId(),
            secondsUntilNextRewardedPrompt = cur.secondsUntilNextRewardedPrompt.coerceAtMost(profile.effectiveCadenceSeconds()),
            profileWarnings = profile.validate()
        )
    }

    fun startForegroundTicker(scope: CoroutineScope) {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                delay(1_000L)
                tickSeconds(1L)
            }
        }
    }

    fun onForegroundChanged(foreground: Boolean) {
        val now = clockMs()
        val cur = _state.value
        val subActive = cur.subscriptionExpiresAtMs > now
        _state.value = cur.copy(
            isForeground = foreground,
            isSubscribed = subActive
        )
    }

    /**
     * Advances timers deterministically by [elapsedSeconds] ONLY when app is foreground
     * and user is not subscribed to the monthly ad-free plan.
     */
    fun tickSeconds(elapsedSeconds: Long = 1L) {
        if (elapsedSeconds <= 0L) return
        val cur = _state.value
        val now = clockMs()
        val subActive = cur.subscriptionExpiresAtMs > now
        val cadenceSecs = cur.profile.effectiveCadenceSeconds()
        val gateSecs = cur.profile.effectiveGateSeconds()

        if (subActive) {
            if (!cur.isSubscribed || cur.showPeriodicAdPrompt || cur.showMandatoryAdGate) {
                _state.value = cur.copy(
                    isSubscribed = true,
                    showPeriodicAdPrompt = false,
                    showMandatoryAdGate = false,
                    foregroundSecondsWithoutAd = 0L
                )
            }
            return
        } else if (cur.isSubscribed) {
            prefs.edit().remove(KEY_SUB_EXPIRES_MS).apply()
        }

        if (!cur.isForeground || cur.isPlayingTestAd || cur.showMandatoryAdGate) {
            return
        }

        val nextNoAd = cur.foregroundSecondsWithoutAd + elapsedSeconds
        val gateTriggered = nextNoAd >= gateSecs

        var nextPromptCountdown = cur.secondsUntilNextRewardedPrompt - elapsedSeconds
        var periodicPrompt = cur.showPeriodicAdPrompt
        if (!gateTriggered && nextPromptCountdown <= 0L) {
            periodicPrompt = true
            nextPromptCountdown = cadenceSecs
        }

        prefs.edit().putLong(KEY_NO_AD_SECS, nextNoAd).apply()
        _state.value = cur.copy(
            isSubscribed = false,
            subscriptionExpiresAtMs = 0L,
            foregroundSecondsWithoutAd = nextNoAd,
            secondsUntilNextRewardedPrompt = nextPromptCountdown.coerceAtLeast(1L),
            showPeriodicAdPrompt = if (gateTriggered) false else periodicPrompt,
            showMandatoryAdGate = gateTriggered
        )
    }

    fun dismissPeriodicPromptLater() {
        val cadence = _state.value.profile.effectiveCadenceSeconds()
        _state.value = _state.value.copy(
            showPeriodicAdPrompt = false,
            secondsUntilNextRewardedPrompt = cadence
        )
    }

    fun startTestRewardedAd(scope: CoroutineScope) {
        if (_state.value.isPlayingTestAd) return
        adPlayerJob?.cancel()
        _state.value = _state.value.copy(
            isPlayingTestAd = true,
            testAdSecondsRemaining = TEST_AD_DURATION_SECONDS,
            showPeriodicAdPrompt = false
        )
        adPlayerJob = scope.launch {
            for (rem in TEST_AD_DURATION_SECONDS downTo 1) {
                _state.value = _state.value.copy(testAdSecondsRemaining = rem)
                delay(1_000L)
            }
            completeTestRewardedAd()
        }
    }

    fun completeTestRewardedAd() {
        adPlayerJob?.cancel()
        val cur = _state.value
        val nextCount = cur.totalTestAdsWatched + 1
        prefs.edit()
            .putLong(KEY_NO_AD_SECS, 0L)
            .putInt(KEY_ADS_WATCHED, nextCount)
            .apply()
        _state.value = cur.copy(
            foregroundSecondsWithoutAd = 0L,
            secondsUntilNextRewardedPrompt = cur.profile.effectiveCadenceSeconds(),
            showPeriodicAdPrompt = false,
            showMandatoryAdGate = false,
            isPlayingTestAd = false,
            testAdSecondsRemaining = 0,
            totalTestAdsWatched = nextCount
        )
    }

    fun activateMonthlySubscriptionTest() {
        val cur = _state.value
        val days = cur.profile.upi.subscriptionDays.toLong().coerceIn(1L, 365L)
        val expires = clockMs() + days * 24L * 60L * 60L * 1000L
        prefs.edit()
            .putLong(KEY_SUB_EXPIRES_MS, expires)
            .putLong(KEY_NO_AD_SECS, 0L)
            .apply()
        _state.value = cur.copy(
            isSubscribed = true,
            subscriptionExpiresAtMs = expires,
            foregroundSecondsWithoutAd = 0L,
            secondsUntilNextRewardedPrompt = cur.profile.effectiveCadenceSeconds(),
            showPeriodicAdPrompt = false,
            showMandatoryAdGate = false,
            isPlayingTestAd = false
        )
    }

    fun cancelSubscriptionForTesting() {
        prefs.edit().remove(KEY_SUB_EXPIRES_MS).apply()
        _state.value = _state.value.copy(
            isSubscribed = false,
            subscriptionExpiresAtMs = 0L
        )
    }

    companion object {
        private const val PREFS_NAME = "studio_monetization"
        private const val KEY_SUB_EXPIRES_MS = "sub_expires_ms"
        private const val KEY_NO_AD_SECS = "fg_no_ad_secs"
        private const val KEY_ADS_WATCHED = "ads_watched"
        private const val KEY_PROFILE_JSON = "monetization_profile_json"

        const val TEST_REWARDED_AD_UNIT_ID = AdMobProfileConfig.DEFAULT_TEST_REWARDED_AD_UNIT_ID
        const val SUBSCRIPTION_PRICE_INR = 300
        const val SUBSCRIPTION_DURATION_MS = 30L * 24L * 60L * 60L * 1000L
        const val REWARDED_AD_CADENCE_SECONDS = 150L
        const val FOREGROUND_NO_AD_LIMIT_SECONDS = 20L * 60L
        const val TEST_AD_DURATION_SECONDS = 5
    }
}
