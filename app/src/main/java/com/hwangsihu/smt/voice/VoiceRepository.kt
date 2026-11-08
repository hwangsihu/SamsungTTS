package com.hwangsihu.smt.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.edit
import java.util.Locale

enum class Engine { CLASSIC, HD, NEURAL }

// SPEAKER is one speaker of a multi-speaker pack; MULTILINGUAL is one speaker reading one of a pack's locales.
enum class Model { SINGLE, SPEAKER, MULTILINGUAL }

sealed interface VoiceSource {
    // Files missing from dir come from sharedDir (SM-P600 voices reuse the Note 2 models).
    data class Asset(val dir: String, val sharedDir: String? = null) : VoiceSource
    // Packs made for the 3.5 engine hand their files only to Samsung's own TTS ("Not allowed uid"), so
    // readFromApk takes them straight from the pack's APK, where they sit uncompressed under assets/.
    data class Pack(
        val packageName: String,
        val sourceDir: String,
        val nativeLibraryDir: String,
        val version: String = "",
        val readFromApk: Boolean = false,
    ) : VoiceSource
}

data class SmtVoice(
    val name: String,
    val locale: Locale,
    val variant: String,
    val label: String,
    val engine: Engine,
    val source: VoiceSource,
    val device: String? = null,
    val model: Model = Model.SINGLE,
)

class VoiceRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("voices", Context.MODE_PRIVATE)

    @Volatile
    private var cache: List<SmtVoice>? = null

    fun voices(): List<SmtVoice> = cache ?: (embedded() + packs()).distinctBy { it.name }.also { cache = it }

    fun invalidate() {
        cache = null
    }

    // Clients that only pick a language get the alias, so they follow later changes of the default.
    fun resolve(name: String?): SmtVoice? = when {
        name == null -> null
        name.endsWith(ALIAS_SUFFIX) -> voices().firstOrNull { aliasOf(it.locale) == name }
            ?.let { defaultVoice(it.locale.isO3Language, null) }
        else -> voices().firstOrNull { it.name == name }
    }

    fun aliases(): List<Pair<String, Locale>> = voices().map { it.locale.language }.distinct()
        .map { language -> aliasOf(Locale.forLanguageTag(language)) to Locale.forLanguageTag(language) }

    fun defaultVoice(iso3Language: String?, iso3Country: String?): SmtVoice? {
        val sameLanguage = voices().filter { it.locale.isO3Language == iso3Language }
        val saved = prefs.getString(key(iso3Language), null)
        return sameLanguage.firstOrNull { it.name == saved }
            ?: sameLanguage.firstOrNull { it.locale.isO3Country == iso3Country }
            ?: sameLanguage.firstOrNull()
    }

    fun defaults(): Map<String, String> = voices().map { it.locale.isO3Language }.distinct()
        .mapNotNull { language -> defaultVoice(language, null)?.let { language to it.name } }
        .toMap()

    fun setDefault(voice: SmtVoice) {
        prefs.edit { putString(key(voice.locale.isO3Language), voice.name) }
    }

    private fun key(iso3Language: String?) = "default_$iso3Language"

    private fun embedded(): List<SmtVoice> = context.assets.list(ASSET_ROOT).orEmpty().flatMap { device ->
        val info = DEVICES[device] ?: Device(device)
        context.assets.list("$ASSET_ROOT/$device").orEmpty().mapNotNull { dir ->
            parse(dir)?.let { (locale, variant) ->
                SmtVoice(
                    "$device-${name(locale, variant)}", locale, variant, "${info.label} $variant", Engine.CLASSIC,
                    VoiceSource.Asset("$ASSET_ROOT/$device/$dir", info.sharesWith?.let { "$ASSET_ROOT/$it/$dir" }),
                    device,
                )
            }
        }
    }

    private fun packs(): List<SmtVoice> {
        val pm = context.packageManager
        return pm.queryIntentContentProviders(Intent(ACTION_CHECK_VOICE_DATA), 0)
            .mapNotNull { it.providerInfo?.packageName }
            .filter { it.startsWith(PACK_PREFIX) }
            .distinct()
            .flatMap { packageName ->
                val app = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val meta = app.metaData ?: return@flatMap emptyList()
                val (locale, variant) = parse(packageName.removePrefix(PACK_PREFIX)) ?: return@flatMap emptyList()
                val label = pm.getApplicationLabel(app).toString()
                val version = pm.getPackageInfo(packageName, 0).versionName.orEmpty()
                val source = VoiceSource.Pack(packageName, app.sourceDir, app.nativeLibraryDir, version)
                val multi = meta.getBoolean("voice.data.provider.multispeaker") || meta.getBoolean("voice.data.provider.multilingual")
                when {
                    meta.getBoolean("voice.data.provider.x") ->
                        listOf(SmtVoice(name(locale, variant), locale, variant, label, Engine.HD, source))
                    multi || meta.getString("voice.data.type") == "neural" -> neuralVoices(meta, locale, variant, label, source)
                    meta.getBoolean("voice.data.provider") ->
                        listOf(SmtVoice(name(locale, variant), locale, variant, label, Engine.CLASSIC, source))
                    else -> emptyList()
                }
            }
    }

    // A multi-speaker pack (ko_kr_r00) lists its speakers in voice.data.supported_voices, such as l05 and g01; a
    // multilingual one also lists the locales each speaker reads in voice.data.supported_locales. Samsung's
    // service offers every speaker (and locale) as a voice, the pack's voice.data.default_voice first.
    private fun neuralVoices(meta: Bundle, locale: Locale, variant: String, label: String, pack: VoiceSource.Pack): List<SmtVoice> {
        val source = pack.copy(readFromApk = true)
        val speakers = values(meta, "voice.data.supported_voices").filter { SPEAKER.matches(it) }
        val locales = values(meta, "voice.data.supported_locales").mapNotNull(::localeOf)
        val model = when {
            speakers.isEmpty() -> Model.SINGLE
            locales.isNotEmpty() -> Model.MULTILINGUAL
            meta.getBoolean("voice.data.provider.multispeaker") -> Model.SPEAKER
            else -> Model.SINGLE
        }
        if (model == Model.SINGLE) {
            return listOf(SmtVoice(name(locale, variant) + NEURAL_SUFFIX, locale, variant, label, Engine.NEURAL, source))
        }
        val preferred = meta.getString("voice.data.default_voice")
        val names = speakerNames(source.packageName)
        return (if (model == Model.MULTILINGUAL) locales else listOf(locale)).flatMap { speakerLocale ->
            speakers.map { id ->
                val speakerLabel = "$label ${names[id] ?: id}"
                SmtVoice(name(speakerLocale, id) + NEURAL_SUFFIX, speakerLocale, id, speakerLabel, Engine.NEURAL, source, model = model)
            }
        }.sortedWith(compareBy({ it.variant != preferred }, { it.label }))
    }

    private fun values(meta: Bundle, key: String) = meta.getString(key).orEmpty().split(':').filter { it.isNotBlank() && it != "NA" }

    // The pack's cfg ends with key/value strings such as "multispeaker_0" -> "voice1:voice 1:음성1:음성 1:l05:F05:두리:0":
    // the English and Korean display names come second and fourth, the speaker id fifth.
    private fun speakerNames(packageName: String): Map<String, String> = runCatching {
        val cfg = context.createPackageContext(packageName, 0).assets.open("cfg").use { it.readBytes() }
        val nameField = if (Locale.getDefault().language == "ko") 3 else 1
        String(cfg, Charsets.UTF_8).split('\u0000').zipWithNext()
            .filter { (key, _) -> key.startsWith("multispeaker_") }
            .mapNotNull { (_, value) ->
                val fields = value.split(':')
                val id = fields.getOrNull(4)?.substringBefore(' ') ?: return@mapNotNull null
                fields.getOrNull(nameField)?.let { id to it }
            }
            .toMap()
    }.getOrDefault(emptyMap())

    private data class Device(val label: String, val sharesWith: String? = null)

    companion object {
        const val ACTION_CHECK_VOICE_DATA = "com.samsung.SMT.action.CHECK_VOICE_DATA"
        // Folders under assets/voices; the voice list shows them in name order.
        const val DEVICE_NOTE2 = "Note2"
        const val DEVICE_P600 = "Note10.1"
        const val DEVICE_E300 = "S4"
        const val DEVICE_J500 = "J5"
        private const val DEVICE_NOTE8 = "Note8"
        private const val ALIAS_SUFFIX = "-default"
        private const val PACK_PREFIX = "com.samsung.SMT.lang_"
        private const val ASSET_ROOT = "voices"
        private val DEVICES = mapOf(
            DEVICE_NOTE2 to Device("Note 2"),
            DEVICE_NOTE8 to Device("Note 8"),
            DEVICE_P600 to Device("Note 10.1 2014", sharesWith = DEVICE_NOTE2),
            DEVICE_E300 to Device("S4"),
            DEVICE_J500 to Device("J5"),
        )
        private const val NEURAL_SUFFIX = "-neural"
        private val ID = Regex("^([a-z]{2})_([a-zA-Z]{2})_([a-zA-Z][0-9]{2})$")
        private val SPEAKER = Regex("^[a-z][0-9]{2}$")

        fun aliasOf(locale: Locale) = "${locale.language}$ALIAS_SUFFIX"

        private fun parse(id: String): Pair<Locale, String>? = ID.matchEntire(id)?.destructured?.let { (language, country, variant) ->
            Locale.Builder().setLanguage(language).setRegion(country.uppercase(Locale.ROOT)).build() to variant
        }

        private fun name(locale: Locale, variant: String) = "${locale.language}-${locale.country}-$variant"

        // "ko_KR", "ko-KR" or the ISO 639-2 form "kor-KOR".
        private fun localeOf(tag: String): Locale? {
            val (language, country) = tag.split('-', '_').takeIf { it.size == 2 } ?: return null
            if (language.length == 2) return Locale.Builder().setLanguage(language).setRegion(country.uppercase(Locale.ROOT)).build()
            return Locale.getAvailableLocales().firstOrNull {
                runCatching { it.isO3Language == language.lowercase(Locale.ROOT) && it.isO3Country == country.uppercase(Locale.ROOT) }
                    .getOrDefault(false)
            }
        }
    }
}
