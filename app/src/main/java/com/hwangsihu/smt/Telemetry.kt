package com.hwangsihu.smt

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.hwangsihu.smt.engine.ClassicSynthesizer
import com.hwangsihu.smt.engine.HdSynthesizer
import com.hwangsihu.smt.engine.LegacySynthesizer
import com.hwangsihu.smt.engine.Synthesizer
import com.hwangsihu.smt.voice.SmtVoice

internal class Telemetry(context: Context) {

    private val prefs = context.getSharedPreferences("telemetry", Context.MODE_PRIVATE)
    private val analytics = if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAnalytics.getInstance(context)
    private val crashlytics get() = FirebaseCrashlytics.getInstance()

    val available get() = analytics != null

    val consent: Boolean? get() = if (prefs.contains(CONSENT)) prefs.getBoolean(CONSENT, false) else null

    fun setConsent(granted: Boolean) {
        prefs.edit { putBoolean(CONSENT, granted) }
        val analytics = analytics ?: return
        crashlytics.deleteUnsentReports()
        crashlytics.setCrashlyticsCollectionEnabled(granted)
        analytics.setAnalyticsCollectionEnabled(granted)
    }

    fun screen(screen: Screen) {
        log(FirebaseAnalytics.Event.SCREEN_VIEW, Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, if (screen is Screen.Voices) "voices" else "languages")
            putString(FirebaseAnalytics.Param.SCREEN_CLASS, MainActivity::class.simpleName)
            if (screen is Screen.Voices) putString("language", screen.iso3)
        })
    }

    fun preferredEngine(preferred: Boolean) {
        if (enabled) analytics?.setUserProperty("preferred_engine", preferred.toString())
    }

    fun selectVoice(voice: SmtVoice) = log("select_voice", params(voice))

    fun playSample(voice: SmtVoice) = log("play_sample", params(voice))

    fun loadingVoice(voice: SmtVoice) {
        if (!enabled) return
        crashlytics.setCustomKey("voice", voice.name)
        crashlytics.setCustomKey("engine", voice.engine.name.lowercase())
        crashlytics.setCustomKey("abi", abi)
    }

    fun voiceLoaded(voice: SmtVoice, synthesizer: Synthesizer, millis: Long) {
        val name = when (synthesizer) {
            is HdSynthesizer -> "hd"
            is LegacySynthesizer -> "legacy"
            is ClassicSynthesizer -> "classic"
            else -> "other"
        }
        log("load_voice", params(voice).apply {
            putString("synthesizer", name)
            putLong("duration_ms", millis)
        })
    }

    fun voiceFailed(voice: SmtVoice, error: Throwable) {
        log("load_voice_failed", params(voice).apply { putString("error", error.javaClass.simpleName) })
        if (enabled) crashlytics.recordException(error)
    }

    private val enabled get() = analytics != null && consent == true

    private fun log(event: String, params: Bundle) {
        if (enabled) analytics?.logEvent(event, params)
    }

    private fun params(voice: SmtVoice) = Bundle().apply {
        putString("voice", voice.name.take(MAX_VALUE))
        putString("language", voice.locale.toLanguageTag())
        putString("engine", voice.engine.name.lowercase())
        putString("abi", abi)
    }

    private val abi get() = if (Process.is64Bit()) "64" else "32"

    private companion object {
        const val CONSENT = "consent"
        const val MAX_VALUE = 100
    }
}
