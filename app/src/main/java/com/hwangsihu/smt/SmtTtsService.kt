package com.hwangsihu.smt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import com.hwangsihu.smt.engine.AudioSink
import com.hwangsihu.smt.engine.Synthesizer
import com.hwangsihu.smt.engine.skippingLeadIn
import com.hwangsihu.smt.voice.Engine
import com.hwangsihu.smt.voice.SmtVoice
import com.hwangsihu.smt.voice.VoiceRepository
import kotlin.math.min

class SmtTtsService : TextToSpeechService() {

    private lateinit var repository: VoiceRepository
    private var loaded: Pair<String, Synthesizer>? = null

    @Volatile
    private var active: Synthesizer? = null
    private var language = arrayOf("kor", "KOR", "")

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = repository.invalidate()
    }

    override fun onCreate() {
        repository = VoiceRepository(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        super.onCreate()
    }

    override fun onDestroy() {
        unregisterReceiver(packageChanges)
        loaded?.second?.release()
        loaded = null
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val sameLanguage = repository.voices().filter { it.locale.isO3Language == lang }
        return when {
            sameLanguage.isEmpty() -> TextToSpeech.LANG_NOT_SUPPORTED
            sameLanguage.any { it.locale.isO3Country == country } -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> = language

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            language = arrayOf(lang.orEmpty(), country.orEmpty(), variant.orEmpty())
        }
        return result
    }

    override fun onGetVoices(): List<Voice> =
        repository.voices().map { it.toVoice() } + repository.aliases().map { (name, locale) ->
            Voice(name, locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
        }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (repository.resolve(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        repository.defaultVoice(lang, country)?.let { VoiceRepository.aliasOf(it.locale) }

    override fun onStop() {
        active?.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val voice = repository.resolve(request.voiceName) ?: repository.defaultVoice(request.language, request.country)
        if (voice == null) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        val synthesizer = try {
            synthesizerFor(voice)
        } catch (e: Exception) {
            Log.e(TAG, "cannot load ${voice.name}", e)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }
        callback.start(synthesizer.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
        val maxChunk = callback.maxBufferSize
        val sink: AudioSink = sink@{ buffer, offset, length ->
            var position = offset
            while (position < offset + length) {
                val size = min(maxChunk, offset + length - position)
                if (callback.audioAvailable(buffer, position, size) != TextToSpeech.SUCCESS) return@sink false
                position += size
            }
            true
        }
        active = synthesizer
        val text = request.charSequenceText.toString()
        val ok = synthesizer.synthesize(text, request.speechRate, request.pitch, sink.skippingLeadIn())
        active = null
        if (ok) callback.done() else callback.error(TextToSpeech.ERROR_SYNTHESIS)
    }

    private fun synthesizerFor(voice: SmtVoice): Synthesizer {
        loaded?.let { (name, synthesizer) ->
            if (name == voice.name) return synthesizer
            synthesizer.release()
        }
        loaded = null
        return Synthesizer.load(this, voice).also { loaded = voice.name to it }
    }

    private fun SmtVoice.toVoice() = Voice(
        name, locale,
        when (engine) {
            Engine.HD -> Voice.QUALITY_VERY_HIGH
            Engine.NEURAL -> Voice.QUALITY_HIGH
            Engine.CLASSIC -> Voice.QUALITY_NORMAL
        },
        Voice.LATENCY_NORMAL, false, emptySet(),
    )

    private companion object {
        const val TAG = "SmtTtsService"
    }
}
