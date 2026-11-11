package com.hwangsihu.smt

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.hwangsihu.smt.ui.theme.SamsungTTSTheme
import com.hwangsihu.smt.voice.SmtVoice
import com.hwangsihu.smt.voice.VoiceRepository
import java.text.Collator
import java.util.Locale

internal data class Language(val iso3: String, val name: String, val voices: List<SmtVoice>)

internal sealed interface Screen {
    data object Languages : Screen
    data class Voices(val iso3: String) : Screen
}

internal fun SnapshotStateList<Screen>.open(language: Language) {
    removeAll { it is Screen.Voices }
    add(Screen.Voices(language.iso3))
}

internal class TelemetryControls(val consent: Boolean?, val onConsent: (Boolean) -> Unit)

class MainActivity : ComponentActivity() {

    private lateinit var repository: VoiceRepository
    private lateinit var telemetry: Telemetry
    private var tts: TextToSpeech? = null
    private val voices = mutableStateOf(emptyList<SmtVoice>())
    private val defaults = mutableStateOf(emptyMap<String, String>())
    private val consent = mutableStateOf<Boolean?>(null)

    // The text-to-speech settings, while this is not the preferred engine and the device has those settings.
    private val ttsSettings = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = VoiceRepository(this)
        telemetry = Telemetry(this)
        consent.value = telemetry.consent
        tts = TextToSpeech(this, {}, packageName)
        val device = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        setContent {
            val controls = if (telemetry.available) TelemetryControls(consent.value, ::answer) else null
            val languages = remember(voices.value) { languages(voices.value) }
            val backStack = rememberBackStack()
            val top = backStack.last()
            LaunchedEffect(top) { telemetry.screen(top) }
            LaunchedEffect(languages) {
                if (languages.isNotEmpty()) backStack.removeAll { it is Screen.Voices && languages.none { language -> language.iso3 == it.iso3 } }
            }
            if (device == Configuration.UI_MODE_TYPE_WATCH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                WatchApp(languages, defaults.value, backStack, controls, onSelect = ::select, onPlay = ::speak)
            } else {
                val television = device == Configuration.UI_MODE_TYPE_TELEVISION
                SamsungTTSTheme(darkTheme = television || isSystemInDarkTheme()) {
                    App(
                        languages, defaults.value, backStack, television, controls, onSelect = ::select, onPlay = ::speak,
                        onOpenTtsSettings = ttsSettings.value?.let { intent -> { startActivity(intent) } },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        repository.invalidate()
        voices.value = repository.voices()
        defaults.value = repository.defaults()
        val preferred = tts?.defaultEngine == packageName
        telemetry.preferredEngine(preferred)
        ttsSettings.value = Intent(ACTION_TTS_SETTINGS).takeIf { !preferred && it.resolveActivity(packageManager) != null }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun answer(granted: Boolean) {
        telemetry.setConsent(granted)
        consent.value = granted
        if (granted) telemetry.preferredEngine(tts?.defaultEngine == packageName)
    }

    private fun select(voice: SmtVoice) {
        if (defaults.value[voice.locale.isO3Language] != voice.name) telemetry.selectVoice(voice)
        repository.setDefault(voice)
        defaults.value = repository.defaults()
    }

    private fun speak(voice: SmtVoice) {
        val engine = tts ?: return
        telemetry.playSample(voice)
        engine.voices?.firstOrNull { it.name == voice.name }?.let { engine.voice = it }
        engine.speak(SampleText.forLanguage(voice.locale.isO3Language), TextToSpeech.QUEUE_FLUSH, null, voice.name)
    }

    private fun languages(voices: List<SmtVoice>): List<Language> {
        val ui = Locale.getDefault()
        val collator = Collator.getInstance(ui)
        return voices.groupBy { it.locale.isO3Language }
            .map { (iso3, group) -> Language(iso3, group.first().locale.getDisplayLanguage(ui), group) }
            .sortedWith(compareBy<Language> { it.voices.first().locale.language != ui.language }.thenComparator { a, b -> collator.compare(a.name, b.name) })
    }
}

@Composable
private fun rememberBackStack(): SnapshotStateList<Screen> = rememberSaveable(
    saver = listSaver(
        save = { stack -> stack.filterIsInstance<Screen.Voices>().map { it.iso3 } },
        restore = { opened -> (listOf<Screen>(Screen.Languages) + opened.map(Screen::Voices)).toMutableStateList() },
    ),
) { mutableStateListOf<Screen>(Screen.Languages) }

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun App(
    languages: List<Language>,
    defaults: Map<String, String>,
    backStack: SnapshotStateList<Screen>,
    television: Boolean,
    telemetry: TelemetryControls?,
    onSelect: (SmtVoice) -> Unit,
    onPlay: (SmtVoice) -> Unit,
    onOpenTtsSettings: (() -> Unit)?,
) {
    val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())
    val twoPane = directive.maxHorizontalPartitions > 1
    val open = (backStack.last() as? Screen.Voices)?.iso3
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(if (television) TV_OVERSCAN else PaddingValues()),
            sceneStrategies = listOf(rememberListDetailSceneStrategy(directive = directive)),
            entryProvider = entryProvider {
                entry<Screen.Languages>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { Placeholder() })) {
                    LanguageScreen(languages, defaults, open, telemetry, onOpenTtsSettings) { language -> backStack.open(language) }
                }
                entry<Screen.Voices>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                    languages.firstOrNull { it.iso3 == key.iso3 }?.let { language ->
                        VoiceScreen(
                            language, defaults[language.iso3],
                            onBack = if (twoPane) null else ({ backStack.removeLastOrNull() }),
                            onSelect = onSelect,
                            onPlay = onPlay,
                        )
                    }
                }
            },
        )
        if (telemetry != null && telemetry.consent == null) ConsentDialog(telemetry.onConsent)
    }
}

private val TV_OVERSCAN = PaddingValues(horizontal = 48.dp, vertical = 27.dp)

private const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

@Composable
private fun InteractionSource.focusColor(): Color {
    val focused by collectIsFocusedAsState()
    return if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
}

@Composable
private fun PreferredEngineCard(onOpenTtsSettings: () -> Unit) {
    val button = remember { MutableInteractionSource() }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
            Text(stringResource(R.string.not_preferred_engine), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.not_preferred_engine_detail),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
            TextButton(
                onClick = onOpenTtsSettings,
                modifier = Modifier.align(Alignment.End),
                border = BorderStroke(2.dp, button.focusColor()),
                interactionSource = button,
            ) {
                Text(stringResource(R.string.open_tts_settings))
            }
        }
    }
}

@Composable
private fun ConsentDialog(onAnswer: (Boolean) -> Unit) {
    val allow = remember { MutableInteractionSource() }
    val dontAllow = remember { MutableInteractionSource() }
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }, border = BorderStroke(2.dp, allow.focusColor()), interactionSource = allow) {
                Text(stringResource(R.string.allow))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }, border = BorderStroke(2.dp, dontAllow.focusColor()), interactionSource = dontAllow) {
                Text(stringResource(R.string.dont_allow))
            }
        },
        title = { Text(stringResource(R.string.consent_title)) },
        text = { Text(stringResource(R.string.consent_body), Modifier.verticalScroll(rememberScrollState())) },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}

@Composable
private fun TelemetrySwitch(telemetry: TelemetryControls) {
    val checked = telemetry.consent == true
    val interaction = remember { MutableInteractionSource() }
    ListItem(
        headlineContent = { Text(stringResource(R.string.telemetry)) },
        supportingContent = { Text(stringResource(R.string.telemetry_detail)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier
            .border(2.dp, interaction.focusColor())
            .toggleable(checked, interaction, ripple(), role = Role.Switch, onValueChange = telemetry.onConsent),
    )
}

@Composable
private fun Placeholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.choose_language), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageScreen(
    languages: List<Language>,
    defaults: Map<String, String>,
    open: String?,
    telemetry: TelemetryControls?,
    onOpenTtsSettings: (() -> Unit)?,
    onOpen: (Language) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (onOpenTtsSettings != null) PreferredEngineCard(onOpenTtsSettings)
            LazyColumn {
                items(languages, key = { it.iso3 }) { language ->
                    val current = language.voices.firstOrNull { it.name == defaults[language.iso3] }
                    val interaction = remember { MutableInteractionSource() }
                    ListItem(
                        headlineContent = { Text(language.name) },
                        supportingContent = {
                            Text(listOfNotNull(current?.label, pluralStringResource(R.plurals.voice_count, language.voices.size, language.voices.size)).joinToString(" · "))
                        },
                        colors = if (language.iso3 == open) {
                            val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                headlineColor = onContainer,
                                supportingColor = onContainer,
                            )
                        } else {
                            ListItemDefaults.colors()
                        },
                        modifier = Modifier
                            .border(2.dp, interaction.focusColor())
                            .clickable(interaction, ripple()) { onOpen(language) },
                    )
                }
                if (telemetry != null) {
                    item(key = "telemetry") {
                        HorizontalDivider()
                        TelemetrySwitch(telemetry)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceScreen(
    language: Language,
    selected: String?,
    onBack: (() -> Unit)?,
    onSelect: (SmtVoice) -> Unit,
    onPlay: (SmtVoice) -> Unit,
) {
    val ui = LocalLocale.current.platformLocale
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(language.name) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.selectableGroup(), contentPadding = padding) {
            items(language.voices, key = { it.name }) { voice ->
                val isSelected = voice.name == selected
                val row = remember { MutableInteractionSource() }
                val play = remember { MutableInteractionSource() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ListItem(
                        headlineContent = { Text(voice.label) },
                        supportingContent = { Text("${voice.locale.getDisplayCountry(ui)} · ${voice.name}") },
                        leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                        modifier = Modifier
                            .weight(1f)
                            .border(2.dp, row.focusColor())
                            .selectable(isSelected, row, ripple(), role = Role.RadioButton) { onSelect(voice) },
                    )
                    TextButton(
                        onClick = { onPlay(voice) },
                        modifier = Modifier.padding(end = 8.dp),
                        border = BorderStroke(2.dp, play.focusColor()),
                        interactionSource = play,
                    ) {
                        Text(stringResource(R.string.play))
                    }
                }
            }
        }
    }
}
