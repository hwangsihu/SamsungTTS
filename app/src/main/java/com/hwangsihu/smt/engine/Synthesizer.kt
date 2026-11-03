package com.hwangsihu.smt.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import androidx.core.net.toUri
import com.hwangsihu.smt.voice.Engine
import com.hwangsihu.smt.voice.SmtVoice
import com.hwangsihu.smt.voice.VoiceSource
import com.samsung.SMT.engine.PowerTTS
import com.samsung.SMT.engine.SmtTTS
import com.samsung.SMT.ref.VoiceDataInfo

typealias AudioSink = (buffer: ByteArray, offset: Int, length: Int) -> Boolean

interface Synthesizer {
    val sampleRate: Int

    fun synthesize(text: String, rate: Int, pitch: Int, sink: AudioSink): Boolean

    fun stop()

    fun release()

    companion object {
        fun load(context: Context, voice: SmtVoice): Synthesizer {
            val files = openFiles(context, voice.source)
            return when (voice.engine) {
                Engine.CLASSIC -> ClassicSynthesizer.load(files, voice)
                Engine.HD -> HdSynthesizer.load(files, (voice.source as VoiceSource.Pack).nativeLibraryDir)
            }
        }

        private fun openFiles(context: Context, source: VoiceSource): Array<VoiceDataInfo> = when (source) {
            is VoiceSource.Asset -> context.assets.list(source.dir).orEmpty().map { name ->
                context.assets.openFd("${source.dir}/$name").use { describe(name, it) }
            }
            is VoiceSource.Pack -> {
                val base = "content://${source.packageName}.VoiceDataProvider"
                val names = context.contentResolver.query(base.toUri(), arrayOf("voice_data_lists"), null, null, null)
                    ?.use { it.columnNames }.orEmpty()
                names.map { name ->
                    val fd = context.contentResolver.openAssetFileDescriptor("$base/$name".toUri(), "r")
                        ?: error("cannot open $name from ${source.packageName}")
                    fd.use { describe(name, it) }
                }
            }
        }.toTypedArray()

        private fun describe(name: String, fd: AssetFileDescriptor) = VoiceDataInfo().apply {
            setFDInfo(fd.parcelFileDescriptor.detachFd(), fd.startOffset, fd.length)
            setFileInfo(name)
        }
    }
}

class ClassicSynthesizer private constructor(private val tts: SmtTTS) : Synthesizer {

    override val sampleRate: Int = tts.samplingRate

    override fun synthesize(text: String, rate: Int, pitch: Int, sink: AudioSink): Boolean {
        tts.setVoiceControls(rate, pitch, 100f, 0)
        return tts.speak(text) { buffer, offset, length -> sink(buffer, offset, length) } == 0
    }

    override fun stop() = tts.stopSpeak()

    override fun release() = tts.release()

    companion object {
        private const val ENGINE = "libsamsungttse.so"

        fun load(files: Array<VoiceDataInfo>, voice: SmtVoice): ClassicSynthesizer {
            val tts = SmtTTS(0)
            check(tts.isValid) { "initialize failed" }
            try {
                check(tts.loadEngine(ENGINE) == 0) { "loadEngine($ENGINE) failed" }
                val result = tts.setLanguageWithFD(
                    files, voice.locale.isO3Language, voice.locale.isO3Country,
                    voice.variant.take(1), voice.variant.drop(1).toInt(), SmtTTS.TTS_TYPE_HMM, 0,
                )
                check(result == 0) { "setLanguageWithFD(${voice.name}) = $result" }
            } catch (e: Exception) {
                tts.release()
                throw e
            }
            return ClassicSynthesizer(tts)
        }
    }
}

class HdSynthesizer private constructor(private val tts: PowerTTS) : Synthesizer {

    @Volatile
    private var stopped = false

    override val sampleRate: Int = tts.samplingRate

    override fun synthesize(text: String, rate: Int, pitch: Int, sink: AudioSink): Boolean {
        stopped = false
        tts.setParameters(rate, pitch, 100f, 0)
        if (tts.speak(text) != 0) return false
        val frame = ByteArray(PowerTTS.BUFFER_SIZE)
        val chunk = ByteArray(PowerTTS.BUFFER_SIZE * 32)
        var filled = 0
        while (!stopped && tts.getSynthesizedData(frame) == 0) {
            frame.copyInto(chunk, filled)
            filled += frame.size
            if (filled == chunk.size) {
                if (!sink(chunk, 0, filled)) return true
                filled = 0
            }
        }
        if (filled > 0 && !stopped) sink(chunk, 0, filled)
        return true
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        tts.terminate()
    }

    companion object {
        fun load(files: Array<VoiceDataInfo>, libraryDir: String): HdSynthesizer {
            val tts = PowerTTS("$libraryDir/libsamsungxtts.so")
            check(tts.initialize() == 0) { "HD initialize failed" }
            val result = tts.setLanguageWithFD(files)
            if (result != 0) {
                tts.terminate()
                error("HD setLanguageWithFD = $result")
            }
            return HdSynthesizer(tts)
        }
    }
}
