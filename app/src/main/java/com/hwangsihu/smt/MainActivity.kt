package com.hwangsihu.smt

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hwangsihu.smt.ui.theme.SamsungTTSTheme
import com.hwangsihu.smt.voice.SmtVoice
import com.hwangsihu.smt.voice.VoiceRepository
import java.text.Collator
import java.util.Locale

private data class Language(val iso3: String, val name: String, val voices: List<SmtVoice>)

class MainActivity : ComponentActivity() {

    private lateinit var repository: VoiceRepository
    private var tts: TextToSpeech? = null
    private val voices = mutableStateOf(emptyList<SmtVoice>())
    private val defaults = mutableStateOf(emptyMap<String, String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = VoiceRepository(this)
        tts = TextToSpeech(this, {}, packageName)
        setContent {
            SamsungTTSTheme {
                val focusManager = LocalFocusManager.current
                var open by rememberSaveable { mutableStateOf<String?>(null) }
                val show = { iso3: String? ->
                    focusManager.clearFocus(force = true)
                    open = iso3
                }
                val languages = languages(voices.value)
                val language = languages.firstOrNull { it.iso3 == open }
                if (language == null) {
                    LanguageScreen(languages, defaults.value, onOpen = { show(it.iso3) })
                } else {
                    BackHandler { show(null) }
                    VoiceScreen(
                        language, defaults.value[language.iso3],
                        onBack = { show(null) },
                        onSelect = { voice ->
                            repository.setDefault(voice)
                            defaults.value = repository.defaults()
                        },
                        onPlay = ::speak,
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
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun speak(voice: SmtVoice, text: String) {
        val engine = tts ?: return
        engine.voices?.firstOrNull { it.name == voice.name }?.let { engine.voice = it }
        engine.speak(text.ifBlank { SampleText.forLanguage(voice.locale.isO3Language) }, TextToSpeech.QUEUE_FLUSH, null, voice.name)
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
private fun LanguageScreen(languages: List<Language>, defaults: Map<String, String>, onOpen: (Language) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = languages.filter { language ->
        query.isBlank() || listOf(
            language.name,
            language.voices.first().locale.getDisplayLanguage(Locale.ENGLISH),
            language.iso3,
            language.voices.first().locale.language,
        ).any { it.contains(query.trim(), ignoreCase = true) }
    }
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_languages)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            LazyColumn {
                items(shown, key = { it.iso3 }) { language ->
                    val current = language.voices.firstOrNull { it.name == defaults[language.iso3] }
                    ListItem(
                        headlineContent = { Text(language.name) },
                        supportingContent = {
                            Text(listOfNotNull(current?.label, stringResource(R.string.voice_count, language.voices.size)).joinToString(" · "))
                        },
                        modifier = Modifier.clickable { onOpen(language) },
                    )
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
    onBack: () -> Unit,
    onSelect: (SmtVoice) -> Unit,
    onPlay: (SmtVoice, String) -> Unit,
) {
    var text by remember(language.iso3) { mutableStateOf("") }
    val ui = Locale.getDefault()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(language.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.text_hint)) },
                    placeholder = { Text(SampleText.forLanguage(language.iso3)) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            items(language.voices, key = { it.name }) { voice ->
                ListItem(
                    headlineContent = { Text(voice.label) },
                    supportingContent = { Text("${voice.locale.getDisplayCountry(ui)} · ${voice.name}") },
                    leadingContent = { RadioButton(selected = voice.name == selected, onClick = { onSelect(voice) }) },
                    trailingContent = { TextButton(onClick = { onPlay(voice, text) }) { Text(stringResource(R.string.play)) } },
                    modifier = Modifier.clickable { onSelect(voice) },
                )
            }
        }
    }
}
