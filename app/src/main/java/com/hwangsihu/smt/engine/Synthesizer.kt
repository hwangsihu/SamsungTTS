package com.hwangsihu.smt.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.util.Log
import android.os.Process
import androidx.core.net.toUri
import com.hwangsihu.smt.voice.Engine
import com.hwangsihu.smt.voice.Model
import com.hwangsihu.smt.voice.SmtVoice
import com.hwangsihu.smt.voice.VoiceRepository
import com.hwangsihu.smt.voice.VoiceSource
import com.samsung.SMT.engine.PowerTTS
import com.samsung.SMT.engine.SmtTTS
import com.samsung.SMT.ref.VoiceDataInfo
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.abs

typealias AudioSink = (buffer: ByteArray, offset: Int, length: Int) -> Boolean

// Drops everything before the first audible sample (|s| > 30, about -60 dBFS): 60-90 ms of near silence
// from the HMM engines, and the 600 ms of zeros the HD engine carries over from the previous utterance.
fun AudioSink.skippingLeadIn(): AudioSink {
    var sounding = false
    return { buffer, offset, length ->
        val end = offset + length
        var start = offset
        if (!sounding) {
            while (start + 1 < end && abs(sampleAt(buffer, start)) <= LEAD_IN_LEVEL) start += 2
            sounding = start + 1 < end
        }
        !sounding || this(buffer, start, end - start)
    }
}

private const val LEAD_IN_LEVEL = 30

private fun sampleAt(buffer: ByteArray, index: Int): Int =
    ((buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)).toShort().toInt()

interface Synthesizer {
    val sampleRate: Int

    fun synthesize(text: String, rate: Int, pitch: Int, sink: AudioSink): Boolean

    fun stop()

    fun release()

    companion object {
        fun load(context: Context, voice: SmtVoice): Synthesizer {
            val files = VoiceFiles(context, voice.source)
            if (voice.engine == Engine.HD) return HdSynthesizer.load(context, files, voice.source as VoiceSource.Pack)
            val legacy = LegacySynthesizer.Kind.entries.firstOrNull { it.device == voice.device }
                ?: LegacySynthesizer.Kind.NOTE2.takeIf { files.isNote2Format() }
            if (legacy != null) {
                runCatching { return LegacySynthesizer.load(context, files, voice, legacy) }
                    .onFailure { Log.e("Synthesizer", "legacy engine unavailable, using the 3.5 engine", it) }
            }
            return ClassicSynthesizer.load(context, files, voice)
        }
    }
}

class VoiceFiles(private val context: Context, private val source: VoiceSource) {

    private val base = (source as? VoiceSource.Pack)?.let { "content://${it.packageName}.VoiceDataProvider" }

    private val assetDirs: Map<String, String> = (source as? VoiceSource.Asset)?.let { asset ->
        listOfNotNull(asset.sharedDir, asset.dir).flatMap { dir -> context.assets.list(dir).orEmpty().map { it to dir } }.toMap()
    }.orEmpty()

    private val apkAssets: AssetManager? = (source as? VoiceSource.Pack)?.takeIf { it.readFromApk }
        ?.let { context.createPackageContext(it.packageName, 0).assets }

    val names: List<String> = when (source) {
        is VoiceSource.Asset -> assetDirs.keys.toList()
        is VoiceSource.Pack -> if (source.readFromApk) {
            ZipFile(source.sourceDir).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }
                    .filter { it.startsWith(APK_ASSETS) && '/' !in it.substring(APK_ASSETS.length) }
                    .map { it.removePrefix(APK_ASSETS) }.toList()
            }
        } else {
            context.contentResolver.query(base!!.toUri(), arrayOf("voice_data_lists"), null, null, null)
                ?.use { it.columnNames.toList() }.orEmpty()
        }
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

    fun forEachDescriptor(action: (name: String, fd: Int, offset: Long, length: Long) -> Unit) {
        for (name in names) open(name).use { action(name, it.parcelFileDescriptor.fd, it.startOffset, it.length) }
    }

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
        is VoiceSource.Asset -> context.assets.openFd("${assetDirs.getValue(name)}/$name")
        is VoiceSource.Pack -> apkAssets?.openFd(name)
            ?: context.contentResolver.openAssetFileDescriptor("$base/$name".toUri(), "r")
            ?: error("cannot open $name from ${source.packageName}")
    }

    private fun stream(name: String): InputStream = when (source) {
        is VoiceSource.Asset -> context.assets.open("${assetDirs.getValue(name)}/$name")
        is VoiceSource.Pack -> apkAssets?.open(name)
            ?: context.contentResolver.openInputStream("$base/$name".toUri())
            ?: error("cannot read $name from ${source.packageName}")
    }

    private companion object {
        const val APK_ASSETS = "assets/"
    }
}

class ClassicSynthesizer private constructor(
    private val tts: SmtTTS,
    private val rateScale: Int,
    private val speechLocale: String?,
) : Synthesizer {

    override val sampleRate: Int = tts.samplingRate

    override fun synthesize(text: String, rate: Int, pitch: Int, sink: AudioSink): Boolean {
        tts.setVoiceControls(rate * rateScale / 100, pitch, 100f, 0)
        val audio = SmtTTS.AudioSink { buffer, offset, length -> sink(buffer, offset, length) }
        val result = if (speechLocale != null) tts.speakWithLocales(text, speechLocale, audio) else tts.speak(text, audio)
        return result == 0
    }

    override fun stop() = tts.stopSpeak()

    override fun release() = tts.release()

    companion object {
        private const val ENGINE = "libsamsungttse.so"
        private const val PACK_ENGINE = "libsamsungtts.so"
        private val UC_TYPES = setOf("s", "h", "x", "y")
        private val NEURAL_TYPES = setOf("l", "g", "p", "r", "v", "F", "M")
        // The engine takes the unit-concatenation voices s and h as F and M, and dictionary voices as f.
        private val NATIVE_TYPES = mapOf("s" to "F", "h" to "M", "d" to "f")
        private val OWN_ENGINE_TYPES = setOf("p", "s", "h")
        private val MULTI_SYNTHESIS_TYPES = setOf("d", "x", "y")
        private val SLOW_KOREAN_PACKS = setOf("4.2.24.30", "4.2.25.17")

        // The 3.5 engine speaks 2014 data about twice as fast; this brings it back to the Note 2 tempo.
        private const val NOTE2_RATE_SCALE = 46

        // Half-precision math makes the neural voices about twice as fast and changes their sound only slightly.
        // Samsung turns it on for the Galaxy A14 alone; here it is on wherever the CPU has it, which needs a
        // 64-bit process.
        private val halfPrecision: Boolean by lazy {
            Process.is64Bit() && runCatching {
                File("/proc/cpuinfo").useLines { lines ->
                    lines.any { it.startsWith("Features") && "asimdhp" in it.substringAfter(':').trim().split(' ') }
                }
            }.getOrDefault(false)
        }

        fun load(context: Context, files: VoiceFiles, voice: SmtVoice): ClassicSynthesizer {
            val rateScale = if (files.isNote2Format()) NOTE2_RATE_SCALE else 100
            val type = voice.variant.take(1)
            val ttsType = ttsType(voice, type)
            val neural = ttsType >= SmtTTS.TTS_TYPE_NEURAL
            val features = if (neural && halfPrecision) SmtTTS.FEATURE_FP16 else 0
            val tts = SmtTTS(0)
            check(tts.isValid) { "initialize failed" }
            try {
                loadEngine(context, tts, voice, type)
                val result = tts.setLanguageWithFD(
                    files.describe(), voice.locale.isO3Language, voice.locale.isO3Country,
                    NATIVE_TYPES[type] ?: type, voice.variant.drop(1).toInt(), ttsType, features,
                )
                check(result == 0) { "setLanguageWithFD(${voice.name}) = $result" }
            } catch (e: Exception) {
                tts.release()
                throw e
            }
            val speechLocale = "${voice.locale.language}-${voice.locale.country}".takeIf { voice.model == Model.MULTILINGUAL }
            return ClassicSynthesizer(tts, rateScale, speechLocale)
        }

        // As Samsung's service decides it: the voice letter names the kind (s, h, x and y are unit concatenation,
        // l, g, p, r, v, F and M neural, d a dictionary voice), a neural pack is neural whatever its letter, and a
        // speaker of a multi-speaker or multilingual pack gets that model's own type.
        private fun ttsType(voice: SmtVoice, type: String): Int {
            val kind = when {
                voice.engine == Engine.NEURAL -> SmtTTS.TTS_TYPE_NEURAL
                type in UC_TYPES -> SmtTTS.TTS_TYPE_UC
                type in NEURAL_TYPES -> SmtTTS.TTS_TYPE_NEURAL
                type == "d" -> SmtTTS.TTS_TYPE_DIC
                else -> SmtTTS.TTS_TYPE_HMM
            }
            if (kind != SmtTTS.TTS_TYPE_NEURAL) return kind
            return when (voice.model) {
                Model.SINGLE -> SmtTTS.TTS_TYPE_NEURAL
                Model.SPEAKER -> SmtTTS.TTS_TYPE_NEURAL_MULTISPEAKER
                Model.MULTILINGUAL -> SmtTTS.TTS_TYPE_NEURAL_MULTILINGUAL
            }
        }

        // A pack that ships its own engine runs on it (ko_kr_s05 crashes the bundled one). Samsung's service
        // still falls back to the bundled engine when the pack's cannot run several voices at once, and for two
        // Korean pack versions that answered TalkBack too slowly; it never does for unit-concatenation voices.
        private fun loadEngine(context: Context, tts: SmtTTS, voice: SmtVoice, type: String) {
            val pack = voice.source as? VoiceSource.Pack
            val own = pack?.let { packLibrary(context, it, PACK_ENGINE) }
            if (own != null && tts.loadEngine(own) == 0) {
                val korean = voice.locale.isO3Language == "kor" && voice.locale.isO3Country == "KOR"
                val fallback = type !in OWN_ENGINE_TYPES && (
                    (type !in MULTI_SYNTHESIS_TYPES && !tts.isSupportMultiInstance) || (korean && pack.version in SLOW_KOREAN_PACKS)
                    )
                if (!fallback) return
            }
            check(tts.loadEngine(ENGINE) == 0) { "loadEngine($ENGINE) failed" }
        }
    }
}

// The 2014 engines, driven the way their own SamsungTTSService did it. A 32-bit process runs the
// engine natively; a 64-bit one runs it through the dynarmic emulator (EmulatedEngine).
class LegacySynthesizer private constructor(
    private val engine: LegacyEngine,
    private val kind: Kind,
    private val locale: Locale,
) : Synthesizer {

    // spectrum and effect are what each device's own SamsungTTSService passed to setParameters.
    enum class Kind(
        val device: String,
        val library: String,
        private val native: () -> LegacyEngine,
        val spectrum: Float,
        val effect: Int,
    ) {
        NOTE2(VoiceRepository.DEVICE_NOTE2, "smt2014", { com.samsung.SMT.SamsungTTSService() }, 0.42f, -1),
        P600(VoiceRepository.DEVICE_P600, "smtp600", { com.samsung.SMX.SamsungTTSService() }, 100f, 0),
        E300(VoiceRepository.DEVICE_E300, "smte300", { com.samsung.SME.SamsungTTSService() }, 100f, 0),
        J500(VoiceRepository.DEVICE_J500, "smtj500", { com.samsung.SMJ.SamsungTTSService() }, 100f, 0);

        private val asset get() = "engines/lib$library.so"

        private var redirected = false

        // The native engine keeps global state, so a process shares one, loaded once.
        private val shared: LegacyEngine by lazy {
            System.loadLibrary(library)
            redirected = VoiceFs.redirect(library)
            native().also { check(it.initialize() != -1) { "$name engine initialize failed" } }
        }

        fun engine(context: Context): LegacyEngine =
            if (!Process.is64Bit()) shared
            else EmulatedEngine(context.assets.open(asset).use { it.readBytes() })
                .also { check(it.initialize() == 0) { "$name emulator failed to load" } }

        // The voice folder to hand the engine: VoiceFs, or copies on disk should the native fopen not redirect.
        fun folder(context: Context, engine: LegacyEngine, files: VoiceFiles, voice: SmtVoice): String = when {
            engine is EmulatedEngine -> engine.serve(files)
            redirected -> VoiceFs.serve(files)
            else -> files.copyTo(File(context.noBackupFilesDir, "voices/${voice.name}")).absolutePath
        }
    }

    @Volatile
    private var stopped = false

    override val sampleRate: Int = engine.samplingRate()

    override fun synthesize(text: String, rate: Int, pitch: Int, sink: AudioSink): Boolean {
        stopped = false
        val utterances = engine.utterances(text, locale)
        if (utterances.isBlank()) return true
        engine.speak(utterances)
        engine.setParameters(rate, pitch, kind.spectrum, kind.effect)
        return pump(sink, engine::getSynthesizedData, { stopped }, keepGoing = { it == 2 || it == 3 })
    }

    override fun stop() {
        stopped = true
    }

    override fun release() = Unit

    companion object {
        fun load(context: Context, files: VoiceFiles, voice: SmtVoice, kind: Kind): LegacySynthesizer {
            val engine = kind.engine(context)
            val result = engine.setLanguage(
                kind.folder(context, engine, files, voice), voice.locale.isO3Language, voice.locale.isO3Country,
                voice.variant.take(1), voice.variant.drop(1).toInt(), 0,
            )
            check(result == 0) { "$kind setLanguage(${voice.name}) = $result" }
            return LegacySynthesizer(engine, kind, voice.locale)
        }

        // The engine's own text normalizer, which the original services ran before speak().
        private fun LegacyEngine.utterances(text: String, locale: Locale): String {
            supervisor_InputText(text, locale.isO3Language, locale.isO3Country)
            return buildString {
                while (true) {
                    val utterance = arrayOfNulls<String>(3)
                    if (!nextUtterance(utterance)) break
                    append(utterance[0].orEmpty())
                }
            }
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
            val tts = PowerTTS(packLibrary(context, pack, LIBRARY) ?: error("${pack.packageName} has no engine for this process"))
            check(tts.initialize() == 0) { "HD initialize failed" }
            val result = tts.setLanguageWithFD(files.describe())
            if (result != 0) {
                tts.terminate()
                error("HD setLanguageWithFD = $result")
            }
            return HdSynthesizer(tts)
        }
    }
}

// The pack's own copy of a native library for this process, or null when the pack has none. Only the pack's
// primary (64-bit) libraries are extracted, so a 32-bit process takes its copy from the pack's APK.
private fun packLibrary(context: Context, pack: VoiceSource.Pack, name: String): String? {
    if (Process.is64Bit()) File(pack.nativeLibraryDir, name).takeIf { it.exists() }?.let { return it.absolutePath }
    val abi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
    val apk = File(pack.sourceDir)
    val version = "${pack.packageName}-${apk.lastModified()}"
    val target = File(context.codeCacheDir, "packs/$version/$abi/$name")
    if (!target.exists()) {
        File(context.codeCacheDir, "packs").listFiles { it.name.startsWith("${pack.packageName}-") && it.name != version }
            ?.forEach { it.deleteRecursively() }
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("lib/$abi/$name") ?: return null
            target.parentFile!!.mkdirs()
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        target.setReadOnly()
    }
    return target.absolutePath
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
