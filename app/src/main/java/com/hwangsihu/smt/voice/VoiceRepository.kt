package com.hwangsihu.smt.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

enum class Engine { CLASSIC, HD }

sealed interface VoiceSource {
    data class Asset(val dir: String) : VoiceSource
    data class Pack(val packageName: String, val sourceDir: String, val nativeLibraryDir: String) : VoiceSource
}

data class SmtVoice(
    val name: String,
    val locale: Locale,
    val variant: String,
    val label: String,
    val engine: Engine,
    val source: VoiceSource,
)

class VoiceRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("voices", Context.MODE_PRIVATE)

    fun voices(): List<SmtVoice> = (embedded() + packs()).distinctBy { it.name }

    fun find(name: String?): SmtVoice? = voices().firstOrNull { it.name == name }

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
        prefs.edit().putString(key(voice.locale.isO3Language), voice.name).apply()
    }

    private fun key(iso3Language: String?) = "default_$iso3Language"

    private fun embedded(): List<SmtVoice> = context.assets.list(ASSET_ROOT).orEmpty().flatMap { device ->
        context.assets.list("$ASSET_ROOT/$device").orEmpty().mapNotNull { dir ->
            parse(dir)?.let { (locale, variant) ->
                SmtVoice(
                    name(locale, variant), locale, variant, "${DEVICES[device] ?: device} $variant",
                    Engine.CLASSIC, VoiceSource.Asset("$ASSET_ROOT/$device/$dir"),
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
            .mapNotNull { packageName ->
                val app = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val meta = app.metaData ?: return@mapNotNull null
                val engine = when {
                    meta.getBoolean("voice.data.provider.x") -> Engine.HD
                    meta.getBoolean("voice.data.provider") -> Engine.CLASSIC
                    else -> return@mapNotNull null
                }
                val (locale, variant) = parse(packageName.removePrefix(PACK_PREFIX)) ?: return@mapNotNull null
                SmtVoice(
                    name(locale, variant), locale, variant, pm.getApplicationLabel(app).toString(), engine,
                    VoiceSource.Pack(packageName, app.sourceDir, app.nativeLibraryDir),
                )
            }
    }

    companion object {
        const val ACTION_CHECK_VOICE_DATA = "com.samsung.SMT.action.CHECK_VOICE_DATA"
        private const val PACK_PREFIX = "com.samsung.SMT.lang_"
        private const val ASSET_ROOT = "voices"
        private val DEVICES = mapOf("note2" to "Note 2", "note8" to "Note 8")
        private val ID = Regex("^([a-z]{2})_([a-zA-Z]{2})_([a-zA-Z][0-9]{2})$")

        private fun parse(id: String): Pair<Locale, String>? = ID.matchEntire(id)?.destructured?.let { (language, country, variant) ->
            Locale.Builder().setLanguage(language).setRegion(country.uppercase(Locale.ROOT)).build() to variant
        }

        private fun name(locale: Locale, variant: String) = "${locale.language}-${locale.country}-$variant"
    }
}
