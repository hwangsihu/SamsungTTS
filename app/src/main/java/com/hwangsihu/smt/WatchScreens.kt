package com.hwangsihu.smt

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.navigation3.rememberSwipeDismissableSceneStrategy
import com.hwangsihu.smt.voice.SmtVoice

// Wear OS: the same two screens with Wear Compose; a swipe or the back button returns to the languages.
@Composable
internal fun WatchApp(
    languages: List<Language>,
    defaults: Map<String, String>,
    backStack: SnapshotStateList<Screen>,
    onSelect: (SmtVoice) -> Unit,
) {
    MaterialTheme(colorScheme = dynamicColorScheme(LocalContext.current) ?: MaterialTheme.colorScheme) {
        AppScaffold {
            NavDisplay(
                backStack = backStack,
                sceneStrategies = listOf(rememberSwipeDismissableSceneStrategy()),
                entryProvider = entryProvider {
                    entry<Screen.Languages> {
                        WatchList(stringResource(R.string.app_name)) { spec ->
                            items(languages, key = { it.iso3 }) { language ->
                                val current = language.voices.firstOrNull { it.name == defaults[language.iso3] }
                                Button(
                                    onClick = { backStack.open(language) },
                                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                    secondaryLabel = { Text(current?.label.orEmpty()) },
                                    transformation = SurfaceTransformation(spec),
                                    label = { Text(language.name) },
                                )
                            }
                        }
                    }
                    entry<Screen.Voices> { key ->
                        languages.firstOrNull { it.iso3 == key.iso3 }?.let { language ->
                            val ui = LocalLocale.current.platformLocale
                            WatchList(language.name) { spec ->
                                items(language.voices, key = { it.name }) { voice ->
                                    RadioButton(
                                        selected = voice.name == defaults[language.iso3],
                                        onSelect = { onSelect(voice) },
                                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                        transformation = SurfaceTransformation(spec),
                                        secondaryLabel = { Text(voice.locale.getDisplayCountry(ui)) },
                                        label = { Text(voice.label) },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun WatchList(title: String, content: TransformingLazyColumnScope.(TransformationSpec) -> Unit) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item {
                ListHeader(Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text(title)
                }
            }
            content(spec)
        }
    }
}
