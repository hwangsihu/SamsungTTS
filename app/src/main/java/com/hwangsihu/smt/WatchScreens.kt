package com.hwangsihu.smt

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialogContent
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SplitRadioButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.navigation3.rememberSwipeDismissableSceneStrategy
import com.hwangsihu.smt.voice.SmtVoice

@Composable
internal fun WatchApp(
    languages: List<Language>,
    defaults: Map<String, String>,
    backStack: SnapshotStateList<Screen>,
    telemetry: TelemetryControls?,
    onSelect: (SmtVoice) -> Unit,
    onPlay: (SmtVoice) -> Unit,
) {
    MaterialTheme(colorScheme = dynamicColorScheme(LocalContext.current) ?: MaterialTheme.colorScheme) {
        AppScaffold {
            if (telemetry != null && telemetry.consent == null) WatchConsent(telemetry.onConsent) else NavDisplay(
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
                            if (telemetry != null) {
                                item {
                                    SwitchButton(
                                        checked = telemetry.consent == true,
                                        onCheckedChange = telemetry.onConsent,
                                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                        transformation = SurfaceTransformation(spec),
                                        secondaryLabel = { Text(stringResource(R.string.telemetry_detail), maxLines = Int.MAX_VALUE) },
                                        label = { Text(stringResource(R.string.telemetry), maxLines = Int.MAX_VALUE) },
                                    )
                                }
                                item {
                                    FilledTonalButton(
                                        onClick = telemetry.onReset,
                                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                        transformation = SurfaceTransformation(spec),
                                        secondaryLabel = { Text(stringResource(R.string.reset_ids_detail), maxLines = Int.MAX_VALUE) },
                                        label = { Text(stringResource(R.string.reset_ids)) },
                                    )
                                }
                            }
                        }
                    }
                    entry<Screen.Voices> { key ->
                        languages.firstOrNull { it.iso3 == key.iso3 }?.let { language ->
                            val ui = LocalLocale.current.platformLocale
                            val play = stringResource(R.string.play)
                            WatchList(language.name) { spec ->
                                items(language.voices, key = { it.name }) { voice ->
                                    SplitRadioButton(
                                        selected = voice.name == defaults[language.iso3],
                                        onSelectionClick = { onSelect(voice) },
                                        selectionContentDescription = voice.label,
                                        onContainerClick = { onPlay(voice) },
                                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                        transformation = SurfaceTransformation(spec),
                                        containerClickLabel = play,
                                        secondaryLabel = { Text(voice.locale.getDisplayCountry(ui)) },
                                        label = {
                                            Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(18.dp))
                                            Text(voice.label)
                                        },
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
private fun WatchConsent(onAnswer: (Boolean) -> Unit) {
    val spec = rememberTransformationSpec()
    AlertDialogContent(
        title = { Text(stringResource(R.string.consent_title)) },
        transformationSpec = spec,
        text = { Text(stringResource(R.string.consent_body)) },
    ) {
        item {
            Button(
                onClick = { onAnswer(true) },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                label = { Text(stringResource(R.string.allow)) },
            )
        }
        item {
            FilledTonalButton(
                onClick = { onAnswer(false) },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                label = { Text(stringResource(R.string.dont_allow)) },
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
