package com.hwangsihu.smt

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hwangsihu.smt.ui.theme.SamsungTTSTheme
import com.hwangsihu.smt.voice.SmtVoice
import com.hwangsihu.smt.voice.VoiceRepository

class MainActivity : ComponentActivity() {

    private lateinit var repository: VoiceRepository
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = VoiceRepository(this)
        tts = TextToSpeech(this, {}, packageName)
        setContent {
            SamsungTTSTheme {
                var defaults by remember { mutableStateOf(repository.defaults()) }
                VoiceScreen(
                    voices = remember { repository.voices() },
                    defaults = defaults,
                    onSelect = { voice ->
                        repository.setDefault(voice)
                        defaults = repository.defaults()
                    },
                    onPlay = ::speak,
                )
            }
        }
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
}

@Composable
private fun VoiceScreen(
    voices: List<SmtVoice>,
    defaults: Map<String, String>,
    onSelect: (SmtVoice) -> Unit,
    onPlay: (SmtVoice, String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.text_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            voices.groupBy { it.locale }.forEach { (locale, group) ->
                item {
                    Text(
                        locale.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                    )
                }
                items(group, key = { it.name }) { voice ->
                    ListItem(
                        headlineContent = { Text(voice.label) },
                        supportingContent = { Text(voice.name) },
                        leadingContent = {
                            RadioButton(
                                selected = defaults[locale.isO3Language] == voice.name,
                                onClick = { onSelect(voice) },
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { onPlay(voice, text) }) { Text(stringResource(R.string.play)) }
                        },
                        modifier = Modifier.clickable { onSelect(voice) },
                    )
                }
            }
        }
    }
}
