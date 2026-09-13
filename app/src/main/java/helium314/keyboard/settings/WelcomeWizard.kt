// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vboard.app.models.ModelDownloadService
import com.vboard.core.model.ByteSize
import com.vboard.core.model.ModelCatalog
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.previewDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LAST_STEP = 4

@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    // WaveKey: the microphone is asked for during setup, not on the
    // first press of the voice key, because dictation is the headline feature
    // and a permission dialog mid-sentence is the worst place to meet it.
    var micAsked by rememberSaveable { mutableStateOf(false) }
    fun micGranted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    fun determineStep(): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) -> 1
        !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) -> 2
        !micAsked && !micGranted() -> 3
        else -> LAST_STEP
    }
    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    val scope = rememberCoroutineScope { Dispatchers.IO }
    LaunchedEffect(step) {
        if (step == 2)
            scope.launch {
                while (step == 2 && !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) {
                    delay(50)
                }
                step = determineStep()
            }
    }
    val useWideLayout = isWideScreen()
    val appName = stringResource(ctx.applicationInfo.labelRes)

    @Composable fun Intro(modifier: Modifier = Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.setup_steps_title, appName),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (JniUtils.sHaveGestureLib)
                Text(
                    stringResource(R.string.setup_welcome_additional_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Text(
                stringResource(R.string.setup_welcome_voice_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable fun Steps(modifier: Modifier = Modifier) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            step = determineStep()
        }
        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            step = LAST_STEP
        }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StepProgress(step)
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    val w = { full: Int -> if (forward) full else -full }
                    (slideInHorizontally(tween(320)) { w(it) } + fadeIn(tween(220)))
                        .togetherWith(slideOutHorizontally(tween(320)) { -w(it) } + fadeOut(tween(160)))
                },
                label = "wizard-step"
            ) { current ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (current) {
                        1 -> StepCard(
                            title = stringResource(R.string.setup_step1_title, appName),
                            instruction = stringResource(R.string.setup_step1_instruction, appName),
                            icon = painterResource(R.drawable.ic_setup_key),
                            actionText = stringResource(R.string.setup_step1_action),
                        ) {
                            launcher.launch(
                                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                    .addCategory(Intent.CATEGORY_DEFAULT)
                            )
                        }

                        2 -> {
                            StepCard(
                                title = stringResource(R.string.setup_step2_title, appName),
                                instruction = stringResource(R.string.setup_step2_instruction, appName),
                                icon = painterResource(R.drawable.ic_setup_select),
                                actionText = stringResource(R.string.setup_step2_action),
                                action = imm::showInputMethodPicker,
                            )
                            SecondaryAction(stringResource(R.string.setup_step3_action), close)
                        }

                        3 -> {
                            StepCard(
                                title = stringResource(R.string.setup_mic_title),
                                instruction = stringResource(R.string.setup_mic_instruction, appName),
                                icon = painterResource(R.drawable.ic_settings_voice),
                                actionText = stringResource(R.string.setup_mic_action),
                            ) {
                                micAsked = true
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            SecondaryAction(stringResource(R.string.setup_mic_skip_action)) {
                                micAsked = true
                                step = LAST_STEP
                            }
                        }

                        else -> {
                            StepCard(
                                title = stringResource(R.string.setup_step3_title),
                                instruction = stringResource(R.string.setup_step3_instruction, appName),
                                icon = painterResource(R.drawable.sym_keyboard_language_switch),
                                actionText = stringResource(R.string.setup_step3_action),
                                action = close,
                            )
                            // WaveKey: the model download is offered here rather than
                            // with the microphone step, because it costs a several-hundred
                            // megabyte download — which is a question, so it is asked as
                            // one, with an answer either way. Yes starts the download and
                            // opens the models screen so it can be watched; no finishes
                            // setup, and the models screen can be reached later.
                            OptionalCard(
                                tiles = listOf(R.drawable.sym_keyboard_voice_rounded, R.drawable.ic_ai_fix),
                                title = stringResource(R.string.setup_voice_action),
                                subtitle = stringResource(
                                    R.string.setup_voice_instruction,
                                    ByteSize.format(
                                        ModelCatalog.packs.filter { it.required }.sumOf { it.totalBytes }
                                    ),
                                ),
                                icon = painterResource(R.drawable.ic_settings_voice),
                            ) {
                                ModelCatalog.packs.filter { it.required }
                                    .forEach { ModelDownloadService.start(ctx, it.id) }
                                SettingsDestination.navigateTo(SettingsDestination.VoiceModels)
                                close()
                            }
                            SecondaryAction(stringResource(R.string.setup_voice_skip_action), finish)
                        }
                    }
                }
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (useWideLayout)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    Intro(Modifier.weight(0.4f))
                    Steps(Modifier.weight(0.6f))
                }
            else
                Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    Intro()
                    Steps()
                }
        }
    }
}

/** Three bars that fill as the user advances. Replaces the old literal "1 2 3" row. */
@Composable
private fun StepProgress(step: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        (1..LAST_STEP).forEach { i ->
            val done = i <= step
            val width by animateDpAsState(if (i == step) 32.dp else 20.dp, label = "step-bar")
            Box(
                Modifier
                    .width(width)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (done) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
            )
        }
    }
}

@Composable
private fun ColumnScope.StepCard(
    title: String,
    instruction: String,
    icon: Painter,
    actionText: String,
    action: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon, null, Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                instruction,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Button(
        onClick = action,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(actionText, style = MaterialTheme.typography.labelLarge)
    }
}

/** An offer the user can take or walk past, so it reads quieter than the step's own action. */
@Composable
private fun OptionalCard(
    title: String,
    subtitle: String,
    icon: Painter,
    tiles: List<Int> = emptyList(),
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The keys the download switches on, in the colours they wear on the
            // keyboard — so the thing being paid for has a face before it lands.
            if (tiles.isEmpty()) {
                Icon(
                    icon, null, Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tiles.forEach { SpectrumTile(it, null, size = 30) }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

@Preview(
    device = "spec:orientation=landscape,width=400dp,height=780dp"
)
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}
