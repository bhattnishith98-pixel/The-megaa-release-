package com.example.monetization

import android.net.Uri
import org.json.JSONObject

/**
 * ============================================================================
 * DEVELOPER MONETIZATION CONFIG (EDIT HERE IN CODE / GITHUB BEFORE RELEASE)
 * ============================================================================
 * End-users CANNOT view or edit these IDs inside the app UI.
 * Change MODE from "TEST" to "LIVE" and replace ADMOB_APP_ID + ADMOB_REWARDED_AD_UNIT_ID
 * (and also update AndroidManifest.xml com.google.android.gms.ads.APPLICATION_ID)
 * before building your final Play Store / Release APK or AAB on GitHub Actions.
 */
object DeveloperMonetizationConfig {
    const val MODE = "TEST" // Change to "LIVE" for production ads
    const val ADMOB_APP_ID = AdMobProfileConfig.DEFAULT_TEST_APP_ID
    const val ADMOB_REWARDED_AD_UNIT_ID = AdMobProfileConfig.DEFAULT_TEST_REWARDED_AD_UNIT_ID
    const val REWARDED_AD_CADENCE_SECONDS = 150L // ~2.5 min foreground prompt
    const val FOREGROUND_NO_AD_GATE_SECONDS = 1200L // 20 min foreground gate
    const val USE_ADMOB_SDK_WHEN_AVAILABLE = true

    const val SUBSCRIPTION_PRICE_INR = 300
    const val SUBSCRIPTION_DAYS = 30
    const val BILLING_MODE = "TEST_LOCAL" // Use "PLAY_BILLING" for Google Play Store
    const val PLAY_BILLING_PRODUCT_ID = "remove_ads_monthly_300"

    // Optional off-Play UPI fields (do NOT use direct UPI for digital subscriptions on Google Play)
    const val OWNER_UPI_ID = ""
    const val OWNER_PAYEE_NAME = "Automation Studio"

    fun toProfile(): MonetizationProfile = MonetizationProfile(
        mode = MODE,
        admob = AdMobProfileConfig(
            appId = ADMOB_APP_ID,
            rewardedAdUnitId = ADMOB_REWARDED_AD_UNIT_ID,
            cadenceSeconds = REWARDED_AD_CADENCE_SECONDS,
            foregroundGateSeconds = FOREGROUND_NO_AD_GATE_SECONDS,
            useSdkWhenAvailable = USE_ADMOB_SDK_WHEN_AVAILABLE
        ),
        upi = UpiProfileConfig(
            upiId = OWNER_UPI_ID,
            payeeName = OWNER_PAYEE_NAME,
            subscriptionAmountInr = SUBSCRIPTION_PRICE_INR,
            subscriptionDays = SUBSCRIPTION_DAYS,
            billingMode = BILLING_MODE,
            playBillingProductId = PLAY_BILLING_PRODUCT_ID
        )
    )
}

data class AdMobProfileConfig(
    val appId: String = DEFAULT_TEST_APP_ID,
    val rewardedAdUnitId: String = DEFAULT_TEST_REWARDED_AD_UNIT_ID,
    val cadenceSeconds: Long = 150L,
    val foregroundGateSeconds: Long = 1200L,
    val useSdkWhenAvailable: Boolean = true
) {
    companion object {
        const val DEFAULT_TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
        const val DEFAULT_TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    }
}

data class UpiProfileConfig(
    val upiId: String = "",
    val payeeName: String = "Automation Studio",
    val merchantCode: String = "",
    val transactionNote: String = "Automation Studio Pro Ad-Free (30 Days)",
    val subscriptionAmountInr: Int = 300,
    val subscriptionDays: Int = 30,
    val billingMode: String = "TEST_LOCAL", // TEST_LOCAL, PLAY_BILLING, RAZORPAY_UPI, DIRECT_UPI_INTENT
    val playBillingProductId: String = "remove_ads_monthly_300",
    val gatewayKeyId: String = ""
)

/**
 * Monetization profile holding AdMob IDs, timers, and Play Billing / UPI subscription details.
 * Defaults strictly to TEST mode so no live ads or real charges occur during development.
 */
data class MonetizationProfile(
    val mode: String = "TEST", // "TEST" or "LIVE"
    val admob: AdMobProfileConfig = AdMobProfileConfig(),
    val upi: UpiProfileConfig = UpiProfileConfig()
) {
    val isTestMode: Boolean
        get() = !mode.equals("LIVE", ignoreCase = true)

    fun effectiveRewardedAdUnitId(): String = if (isTestMode) {
        AdMobProfileConfig.DEFAULT_TEST_REWARDED_AD_UNIT_ID
    } else {
        admob.rewardedAdUnitId.trim().ifBlank { AdMobProfileConfig.DEFAULT_TEST_REWARDED_AD_UNIT_ID }
    }

    fun effectiveCadenceSeconds(): Long = admob.cadenceSeconds.coerceIn(30L, 600L)

    fun effectiveGateSeconds(): Long = admob.foregroundGateSeconds.coerceIn(120L, 3600L)

    fun buildUpiPaymentUri(): String? {
        val pa = upi.upiId.trim()
        if (pa.isBlank()) return null
        val pn = Uri.encode(upi.payeeName.ifBlank { "Automation Studio" })
        val tn = Uri.encode(upi.transactionNote.ifBlank { "Pro Subscription" })
        val am = upi.subscriptionAmountInr.coerceAtLeast(1)
        val mc = if (upi.merchantCode.isNotBlank()) "&mc=${Uri.encode(upi.merchantCode.trim())}" else ""
        return "upi://pay?pa=${Uri.encode(pa)}&pn=$pn$mc&tn=$tn&am=$am&cu=INR"
    }

    fun validate(): List<String> {
        val warnings = mutableListOf<String>()
        val appIdRegex = Regex("""^ca-app-pub-\d{16}~\d{10}$""")
        val unitRegex = Regex("""^ca-app-pub-\d{16}/\d{10}$""")
        val upiRegex = Regex("""^[a-zA-Z0-9._-]{2,}@[a-zA-Z0-9]{2,}$""")

        if (!appIdRegex.matches(admob.appId.trim())) {
            warnings += "admob.app_id format invalid (expected ca-app-pub-16digits~10digits)."
        }
        if (!unitRegex.matches(admob.rewardedAdUnitId.trim())) {
            warnings += "admob.rewarded_ad_unit_id format invalid (expected ca-app-pub-16digits/10digits)."
        }
        if (upi.upiId.isNotBlank() && !upiRegex.matches(upi.upiId.trim())) {
            warnings += "upi.upi_id '${upi.upiId}' does not match standard UPI VPA format (example: name@okaxis)."
        }
        if (upi.subscriptionAmountInr <= 0) {
            warnings += "upi.subscription_amount_inr must be greater than 0."
        }
        if (!isTestMode && admob.rewardedAdUnitId.trim() == AdMobProfileConfig.DEFAULT_TEST_REWARDED_AD_UNIT_ID) {
            warnings += "mode is LIVE, but admob.rewarded_ad_unit_id is still Google's Test Ad Unit."
        }
        if (upi.upiId.isNotBlank() && upi.billingMode.equals("DIRECT_UPI_INTENT", ignoreCase = true)) {
            warnings += "Play Policy Notice: Digital ad-removal subscriptions distributed on Google Play Store must use Google Play Billing (PLAY_BILLING); use direct UPI / Payment Gateway only for off-Play distribution."
        }
        return warnings
    }

    fun toPrettyJson(): String {
        val admobJson = JSONObject().apply {
            put("app_id", admob.appId)
            put("rewarded_ad_unit_id", admob.rewardedAdUnitId)
            put("cadence_seconds", admob.cadenceSeconds)
            put("foreground_gate_seconds", admob.foregroundGateSeconds)
            put("use_sdk_when_available", admob.useSdkWhenAvailable)
        }
        val upiJson = JSONObject().apply {
            put("upi_id", upi.upiId)
            put("payee_name", upi.payeeName)
            put("merchant_code", upi.merchantCode)
            put("transaction_note", upi.transactionNote)
            put("subscription_amount_inr", upi.subscriptionAmountInr)
            put("subscription_days", upi.subscriptionDays)
            put("billing_mode", upi.billingMode)
            put("play_billing_product_id", upi.playBillingProductId)
            put("gateway_key_id", upi.gatewayKeyId)
        }
        return JSONObject().apply {
            put("mode", if (isTestMode) "TEST" else "LIVE")
            put("admob", admobJson)
            put("upi", upiJson)
        }.toString(2)
    }

    companion object {
        fun defaultTestProfile(): MonetizationProfile = DeveloperMonetizationConfig.toProfile()

        fun fromJson(raw: String): Result<MonetizationProfile> = runCatching {
            val root = JSONObject(raw.trim())
            val mode = root.optString("mode", "TEST").uppercase().let {
                if (it == "LIVE") "LIVE" else "TEST"
            }
            val admobObj = root.optJSONObject("admob") ?: JSONObject()
            val upiObj = root.optJSONObject("upi") ?: JSONObject()

            val admob = AdMobProfileConfig(
                appId = admobObj.optString("app_id", AdMobProfileConfig.DEFAULT_TEST_APP_ID)
                    .ifBlank { AdMobProfileConfig.DEFAULT_TEST_APP_ID },
                rewardedAdUnitId = admobObj.optString("rewarded_ad_unit_id", AdMobProfileConfig.DEFAULT_TEST_REWARDED_AD_UNIT_ID)
                    .ifBlank { AdMobProfileConfig.DEFAULT_TEST_REWARDED_AD_UNIT_ID },
                cadenceSeconds = admobObj.optLong("cadence_seconds", 150L).coerceIn(30L, 600L),
                foregroundGateSeconds = admobObj.optLong("foreground_gate_seconds", 1200L).coerceIn(120L, 3600L),
                useSdkWhenAvailable = admobObj.optBoolean("use_sdk_when_available", true)
            )
            val upi = UpiProfileConfig(
                upiId = upiObj.optString("upi_id", "").trim(),
                payeeName = upiObj.optString("payee_name", "Automation Studio").ifBlank { "Automation Studio" },
                merchantCode = upiObj.optString("merchant_code", "").trim(),
                transactionNote = upiObj.optString("transaction_note", "Automation Studio Pro Ad-Free (30 Days)"),
                subscriptionAmountInr = upiObj.optInt("subscription_amount_inr", 300).coerceAtLeast(1),
                subscriptionDays = upiObj.optInt("subscription_days", 30).coerceIn(1, 365),
                billingMode = upiObj.optString("billing_mode", "TEST_LOCAL").ifBlank { "TEST_LOCAL" },
                playBillingProductId = upiObj.optString("play_billing_product_id", "remove_ads_monthly_300"),
                gatewayKeyId = upiObj.optString("gateway_key_id", "").trim()
            )
            MonetizationProfile(mode = mode, admob = admob, upi = upi)
        }
    }
}
