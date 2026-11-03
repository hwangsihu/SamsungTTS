package com.hwangsihu.smt.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.Process
import androidx.core.net.toUri
import com.hwangsihu.smt.voice.Engine
import com.hwangsihu.smt.voice.SmtVoice
import com.hwangsihu.smt.voice.VoiceSource
import com.samsung.SMT.SamsungTTSService
import com.samsung.SMT.engine.PowerTTS
import com.samsung.SMT.engine.SmtTTS
import com.samsung.SMT.ref.VoiceDataInfo
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

typealias AudioSink = (buffer: ByteArray, offset: Int, length: Int) -> Boolean

interface Synthesizer {
    val sampleRate: Int

    fun synthesize(text: String, rate: Int, pitch: Int, sink: AudioSink): Boolean

    fun stop()

    fun release()

    companion object {
        fun load(context: Context, voice: SmtVoice): Synthesizer {
            val files = VoiceFiles(context, voice.source)
            return when {
                voice.engine == Engine.HD -> HdSynthesizer.load(context, files, voice.source as VoiceSource.Pack)
                files.isNote2Format() && Note2Synthesizer.available -> Note2Synthesizer.load(context, files, voice)
                else -> ClassicSynthesizer.load(files, voice)
            }
        }
    }
}

class VoiceFiles(private val context: Context, private val source: VoiceSource) {

    private val base = (source as? VoiceSource.Pack)?.let { "content://${it.packageName}.VoiceDataProvider" }

    val names: List<String> = when (source) {
        is VoiceSource.Asset -> context.assets.list(source.dir).orEmpty().toList()
        is VoiceSource.Pack -> context.contentResolver.query(base!!.toUri(), arrayOf("voice_data_lists"), null, null, null)
            ?.use { it.columnNames.toList() }.orEmpty()
    }

    // 2014-era HMM voices (Note 2) ship an 852-byte config; later ones are several KB.
    fun isNote2Format(): Boolean = names.filter { it.endsWith(".cfg") }.any { name -> open(name).use { it.length < 1024 } }

    fun describe(): Array<VoiceDataInfo> = names.map { name ->
        open(name).use { fd ->
            VoiceDataInfo().apply {
                setFDInfo(fd.parcelFileDescriptor.detachFd(), fd.startOffset, fd.length)
                setFileInfo(name)
            }
        }
    }.toTypedArray()

    fun copyTo(dir: File): File {
        dir.mkdirs()
        for (name in names) {
            val target = File(dir, name)
            val length = open(name).use { it.length }
            if (target.length() != length) {
                stream(name).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        return dir
    }

    private fun open(name: String): AssetFileDescriptor = when (source) {
        is VoiceSource.Asset -> context.assets.openFd("${source.dir}/$name")
        is VoiceSource.Pack -> context.contentResolver.openAssetFileDescriptor("$base/$name".toUri(), "r")
            ?: error("cannot open $name from ${source.packageName}")
    }

    private fun stream(name: String): InputStream = when (source) {
        is VoiceSource.Asset -> context.assets.open("${source.dir}/$name")
        is VoiceSource.Pack -> context.contentResolver.openInputStream("$base/$name".toUri())
            ?: error("cannot read $name from ${source.packageName}")
    }
}

class ClassicSynthesizer private constructor(private val tts: SmtTTS, private val rateScale: Int) : Synthesizer {

    override val sampleRate: Int = tts.samplingRate

    override fun synthesize(text: String, rate: Int, pitch: Int, sink: AudioSink): Boolean {
        tts.setVoiceControls(rate * rateScale / 100, pitch, 100f, 0)
        return tts.speak(text) { buffer, offset, length -> sink(buffer, offset, length) } == 0
    }

    override fun stop() = tts.stopSpeak()

    override fun release() = tts.release()

    companion object {
        private const val ENGINE = "libsamsungttse.so"

        // The 3.5 engine speaks 2014 data about twice as fast; this brings it back to the Note 2 tempo.
        private const val NOTE2_RATE_SCALE = 46

        fun load(files: VoiceFiles, voice: SmtVoice): ClassicSynthesizer {
            val rateScale = if (files.isNote2Format()) NOTE2_RATE_SCALE else 100
            val tts = SmtTTS(0)
            check(tts.isValid) { "initialize failed" }
            try {
                check(tts.loadEngine(ENGINE) == 0) { "loadEngine($ENGINE) failed" }
                val result = tts.setLanguageWithFD(
                    files.describe(), voice.locale.isO3Language, voice.locale.isO3Country,
                    voice.variant.take(1), voice.variant.drop(1).toInt(), SmtTTS.TTS_TYPE_HMM, 0,
                )
                check(result == 0) { "setLanguageWithFD(${voice.name}) = $result" }
            } catch (e: Exception) {
                tts.release()
                throw e
            }
            return ClassicSynthesizer(tts, rateScale)
        }
    }
}

class Note2Synthesizer private constructor() : Synthesizer {

    @Volatile
    private var stopped = false

    override val sampleRate = 16000

    override fun synthesize(text: String, rate: Int, pitch: Int, sink: AudioSink): Boolean {
        stopped = false
        engine.speak(text)
        engine.setParameters(rate, pitch, ALPHA, NO_EFFECT)
        return pump(sink, { engine.getSynthesizedData(it) }, { stopped }, keepGoing = { it == 2 || it == 3 })
    }

    override fun stop() {
        stopped = true
    }

    override fun release() = Unit

    companion object {
        private const val ALPHA = 0.42f
        private const val NO_EFFECT = -1

        private val engine: SamsungTTSService by lazy {
            System.loadLibrary("smt2014")
            SamsungTTSService().also { check(it.initialize() != -1) { "2014 engine initialize failed" } }
        }

        val available: Boolean by lazy { !Process.is64Bit() && runCatching { engine }.isSuccess }

        fun load(context: Context, files: VoiceFiles, voice: SmtVoice): Note2Synthesizer {
            val dir = files.copyTo(File(context.noBackupFilesDir, "voices/${voice.name}"))
            val result = engine.setLanguage(
                dir.absolutePath, voice.locale.isO3Language, voice.locale.isO3Country,
                voice.variant.take(1), voice.variant.drop(1).toInt(), 0,
            )
            check(result == 0) { "2014 setLanguage(${voice.name}) = $result" }
            return Note2Synthesizer()
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
        return pump(sink, { tts.getSynthesizedData(it) }, { stopped }, keepGoing = { false })
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        tts.terminate()
    }

    companion object {
        private const val LIBRARY = "libsamsungxtts.so"

        fun load(context: Context, files: VoiceFiles, pack: VoiceSource.Pack): HdSynthesizer {
            val tts = PowerTTS(library(context, pack))
            check(tts.initialize() == 0) { "HD initialize failed" }
            val result = tts.setLanguageWithFD(files.describe())
            if (result != 0) {
                tts.terminate()
                error("HD setLanguageWithFD = $result")
            }
            return HdSynthesizer(tts)
        }

        // A 32-bit process can't use the pack's extracted 64-bit library, so take the 32-bit one from its APK.
        private fun library(context: Context, pack: VoiceSource.Pack): String {
            if (Process.is64Bit()) return "${pack.nativeLibraryDir}/$LIBRARY"
            val apk = File(pack.sourceDir)
            val target = File(context.codeCacheDir, "xtts/${pack.packageName}-${apk.lastModified()}/$LIBRARY")
            if (!target.exists()) {
                target.parentFile!!.mkdirs()
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("lib/armeabi-v7a/$LIBRARY") ?: error("${pack.packageName} has no 32-bit engine")
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
                target.setReadOnly()
            }
            return target.absolutePath
        }
    }
}

// Reads 160-byte frames while the engine returns 0 (more audio) or a code keepGoing accepts.
private fun pump(sink: AudioSink, read: (ByteArray) -> Int, stopped: () -> Boolean, keepGoing: (Int) -> Boolean): Boolean {
    val frame = ByteArray(PowerTTS.BUFFER_SIZE)
    val chunk = ByteArray(PowerTTS.BUFFER_SIZE * 32)
    var filled = 0
    while (!stopped()) {
        val code = read(frame)
        if (code != 0) {
            if (keepGoing(code)) continue
            break
        }
        frame.copyInto(chunk, filled)
        filled += frame.size
        if (filled == chunk.size) {
            if (!sink(chunk, 0, filled)) return true
            filled = 0
        }
    }
    if (filled > 0 && !stopped()) sink(chunk, 0, filled)
    return true
}
