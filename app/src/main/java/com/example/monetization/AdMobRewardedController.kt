package com.example.monetization

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdMobSdkState(
    val sdkInitialized: Boolean = false,
    val isAdLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val activeAdUnitId: String = AdMobProfileConfig.DEFAULT_TEST_REWARDED_AD_UNIT_ID,
    val lastStatus: String = "GMA SDK not initialized yet"
)

/**
 * Real Google Mobile Ads (play-services-ads) Rewarded Ad controller.
 * Keeps TEST Ad Unit active while profile.isTestMode is true and gracefully falls back
 * to the in-app 5s Test Ad dialog if offline or running in a headless/non-GMS environment.
 */
class AdMobRewardedController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rewardedAd: RewardedAd? = null

    private val _sdkState = MutableStateFlow(AdMobSdkState())
    val sdkState: StateFlow<AdMobSdkState> = _sdkState.asStateFlow()

    fun initializeSdk(initialAdUnitId: String = AdMobProfileConfig.DEFAULT_TEST_REWARDED_AD_UNIT_ID) {
        if (_sdkState.value.sdkInitialized) {
            preloadRewardedAd(initialAdUnitId)
            return
        }
        sdkScope.launch {
            runCatching {
                MobileAds.initialize(context.applicationContext) {
                    runOnMain {
                        _sdkState.value = _sdkState.value.copy(
                            sdkInitialized = true,
                            activeAdUnitId = initialAdUnitId,
                            lastStatus = "GMA SDK initialized (TEST/Safe Ready)"
                        )
                        preloadRewardedAd(initialAdUnitId)
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "MobileAds.initialize unavailable in current runtime: ${err.message}")
                runOnMain {
                    _sdkState.value = _sdkState.value.copy(
                        sdkInitialized = false,
                        activeAdUnitId = initialAdUnitId,
                        lastStatus = "SDK fallback mode (${err.message ?: "no GMS"})"
                    )
                }
            }
        }
    }

    fun preloadRewardedAd(adUnitId: String) {
        if (_sdkState.value.isLoading) return
        val targetUnit = adUnitId.trim().ifBlank { AdMobProfileConfig.DEFAULT_TEST_REWARDED_AD_UNIT_ID }
        if (rewardedAd != null && _sdkState.value.activeAdUnitId == targetUnit) return

        rewardedAd = null
        _sdkState.value = _sdkState.value.copy(
            isLoading = true,
            isAdLoaded = false,
            activeAdUnitId = targetUnit,
            lastStatus = "Loading rewarded ad ($targetUnit)..."
        )

        runOnMain {
            runCatching {
                val request = AdRequest.Builder().build()
                RewardedAd.load(
                    context.applicationContext,
                    targetUnit,
                    request,
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(ad: RewardedAd) {
                            rewardedAd = ad
                            _sdkState.value = _sdkState.value.copy(
                                isLoading = false,
                                isAdLoaded = true,
                                activeAdUnitId = targetUnit,
                                lastStatus = "GMA RewardedAd loaded ($targetUnit)"
                            )
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            rewardedAd = null
                            _sdkState.value = _sdkState.value.copy(
                                isLoading = false,
                                isAdLoaded = false,
                                activeAdUnitId = targetUnit,
                                lastStatus = "Ad load fallback (${error.code}: ${error.message})"
                            )
                        }
                    }
                )
            }.onFailure { err ->
                rewardedAd = null
                _sdkState.value = _sdkState.value.copy(
                    isLoading = false,
                    isAdLoaded = false,
                    activeAdUnitId = targetUnit,
                    lastStatus = "Ad SDK fallback (${err.message ?: "unavailable"})"
                )
            }
        }
    }

    fun showRewardedAdOrFallback(
        activity: Activity?,
        adUnitId: String,
        useSdkWhenAvailable: Boolean,
        onRewardEarned: () -> Unit,
        onFallbackToTestPlayer: () -> Unit
    ) {
        val loaded = rewardedAd
        if (!useSdkWhenAvailable || loaded == null || activity == null || activity.isFinishing) {
            preloadRewardedAd(adUnitId)
            onFallbackToTestPlayer()
            return
        }

        rewardedAd = null
        _sdkState.value = _sdkState.value.copy(isAdLoaded = false)

        loaded.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preloadRewardedAd(adUnitId)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                _sdkState.value = _sdkState.value.copy(
                    lastStatus = "Ad failed to show (${adError.message}); using fallback player"
                )
                preloadRewardedAd(adUnitId)
                onFallbackToTestPlayer()
            }
        }

        runCatching {
            loaded.show(activity) {
                onRewardEarned()
            }
        }.onFailure {
            onFallbackToTestPlayer()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "AdMobRewardedCtrl"
    }
}
