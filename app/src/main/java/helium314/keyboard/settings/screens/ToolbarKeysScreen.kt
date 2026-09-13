// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.edit
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.checkAndConvertCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.hiddenToolbarKeys
import helium314.keyboard.latin.utils.defaultClipboardToolbarPref
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.defaultToolbarPref
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKeyLongClick
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.utils.readCustomKeyCodes
import helium314.keyboard.latin.utils.writeCustomKeyCodes
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.painterResourceCompat
import helium314.keyboard.settings.preferences.PreferenceCategory
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val CHIP = 44.dp
private val CHIP_GAP = 4.dp
/** How far a key has to leave the row before letting go takes it off the strip. */
private val PULL_OUT = 56.dp

/**
 * WaveKey: the strip is the canvas (docs/settings-ia.md).
 *
 * The row at the top is the real object: keys are dragged inside it to reorder,
 * dragged out of it to switch off, and long-pressed to open everything else
 * about them. The keys that do not fit this phone are drawn dimmed behind a
 * chevron with a count, so the overflow is stated instead of hidden inside a
 * scroll view. Voice is drawn locked, because it is the one key that cannot be
 * removed and so should never appear as a switch you can hunt for later.
 */
@Composable
fun ToolbarKeysScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var target by remember { mutableStateOf(Target.MAIN) }
    var entries by remember(target) { mutableStateOf(readEntries(prefs, target)) }
    var sheetKey by remember { mutableStateOf<ToolbarKey?>(null) }

    fun write(new: List<Pair<ToolbarKey, Boolean>>) {
        entries = new
        prefs.edit {
            putString(target.pref, new.joinToString(Separators.ENTRY) { it.first.name + Separators.KV + it.second })
        }
    }

    val enabled = entries.filter { it.second }.map { it.first }
    val disabled = entries.filterNot { it.second }.map { it.first }
    // How many 36dp keys the real strip can show before the rest spill over.
    val fits = (ctx.resources.displayMetrics.widthPixels /
            ctx.resources.getDimension(R.dimen.config_suggestions_strip_edge_key_width)).toInt().coerceAtLeast(1)
    val spill = (enabled.size - fits).coerceAtLeast(0)

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.wk_toolbar_keys_title),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Target.entries.forEach { t ->
                        SelectChip(stringResource(t.label), t == target, countOf(prefs, t)) {
                            target = t
                        }
                    }
                }

                Strip(
                    keys = enabled,
                    fits = fits,
                    onReorder = { from, to -> write(move(entries, enabled, from, to)) },
                    onRemove = { key -> write(entries.map { if (it.first == key) it.first to false else it }) },
                    onOpen = { sheetKey = it },
                )

                Text(
                    if (spill == 0) stringResource(R.string.wk_toolbar_fit_all, enabled.size)
                    else stringResource(R.string.wk_toolbar_fit, fits, spill),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (spill == 0) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                Text(
                    stringResource(R.string.wk_toolbar_drag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                PreferenceCategory(stringResource(R.string.wk_toolbar_keys_available))
                Group.entries.forEach { group ->
                    val inGroup = disabled.filter { it.group() == group }
                    if (inGroup.isEmpty()) return@forEach
                    Text(
                        stringResource(group.label),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
                    )
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        inGroup.chunked(6).forEach { row ->
                            Row(
                                Modifier.padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)
                            ) {
                                row.forEach { key ->
                                    KeyChip(key, Modifier.combinedClickable(
                                        onClick = { write(entries.map { if (it.first == key) it.first to true else it }) },
                                        onLongClick = { sheetKey = key },
                                    ))
                                }
                            }
                        }
                    }
                    // The fork's own keys are new to everyone; the rest are named
                    // after what they already do on other keyboards.
                    inGroup.filter { it.about() != null }.forEach {
                        Text(
                            stringResource(it.about()!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }

    val open = sheetKey
    if (open != null)
        KeySheet(
            key = open,
            onStrip = open in enabled,
            onDismissRequest = { sheetKey = null },
            onTakeOff = {
                write(entries.map { if (it.first == open) it.first to false else it })
                sheetKey = null
            },
        )
}

/** The strip being edited, drawn in the order it will appear. */
@Composable
private fun Strip(
    keys: List<ToolbarKey>,
    fits: Int,
    onReorder: (Int, Int) -> Unit,
    onRemove: (ToolbarKey) -> Unit,
    onOpen: (ToolbarKey) -> Unit,
) {
    val density = LocalDensity.current
    val pitchPx = with(density) { (CHIP + CHIP_GAP).toPx() }
    val pullOutPx = with(density) { PULL_OUT.toPx() }
    var dragging by remember { mutableStateOf<ToolbarKey?>(null) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }

    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .horizontalScroll(rememberScrollState())
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (keys.isEmpty())
            Text(
                stringResource(R.string.wk_toolbar_keys_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        keys.forEachIndexed { index, key ->
            val locked = false
            val beingDragged = dragging == key
            // Past the fit line the key is drawn faint: it is on the strip, but
            // behind the chevron rather than on screen.
            val spilled = index >= fits
            KeyChip(
                key = key,
                modifier = Modifier
                    .alpha(if (spilled && !beingDragged) 0.4f else 1f)
                    .graphicsLayer {
                        if (beingDragged) {
                            translationX = dx
                            translationY = dy
                            scaleX = 1.08f
                            scaleY = 1.08f
                        }
                    }
                    .combinedClickable(onClick = { onOpen(key) }, onLongClick = { onOpen(key) })
                    .then(
                        if (locked) Modifier
                        else Modifier.pointerInput(key, keys) {
                            detectDragGestures(
                                onDragStart = { dragging = key; dx = 0f; dy = 0f },
                                onDragCancel = { dragging = null; dx = 0f; dy = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dx += amount.x
                                    dy += amount.y
                                },
                                onDragEnd = {
                                    if (abs(dy) > pullOutPx) onRemove(key)
                                    else {
                                        val to = (index + (dx / pitchPx).roundToInt())
                                            .coerceIn(0, keys.lastIndex)
                                        if (to != index) onReorder(index, to)
                                    }
                                    dragging = null; dx = 0f; dy = 0f
                                },
                            )
                        }
                    ),
                locked = locked,
            )
        }
        val spill = keys.size - fits
        if (spill > 0)
            Box(
                Modifier.size(CHIP)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "+$spill",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
    }
}

/** Everything about one key, in place of the codes dialog and the pinned list. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun KeySheet(
    key: ToolbarKey,
    onStrip: Boolean,
    onDismissRequest: () -> Unit,
    onTakeOff: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var code by remember { mutableStateOf(TextFieldValue(getCodeForToolbarKey(key).toString())) }
    var longPressCode by remember { mutableStateOf(TextFieldValue(getCodeForToolbarKeyLongClick(key).toString())) }
    var pinned by remember {
        mutableStateOf(readEntries(prefs, Target.PINNED).any { it.first == key && it.second })
    }

    fun saveCodes() {
        val codes = readCustomKeyCodes(prefs)
        val c = code.text.toIntOrNull()?.takeIf { it.checkAndConvertCode(false) <= Char.MAX_VALUE.code }
        val l = longPressCode.text.toIntOrNull()?.takeIf { it.checkAndConvertCode(true) <= Char.MAX_VALUE.code }
        if (c == null || l == null) return
        codes[key] = c to l
        writeCustomKeyCodes(prefs, codes)
    }

    ModalBottomSheet(
        onDismissRequest = { saveCodes(); onDismissRequest() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyChip(key, Modifier)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        key.name.lowercase(Locale.US).getStringResourceOrName("", ctx),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (key in hiddenToolbarKeys)
                        Text(
                            stringResource(R.string.wk_key_locked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                }
            }
            key.about()?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.key_code), Modifier.weight(0.5f))
                TextField(
                    value = code,
                    onValueChange = { code = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(0.5f)
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.long_press_code), Modifier.weight(0.5f))
                TextField(
                    value = longPressCode,
                    onValueChange = { longPressCode = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(0.5f)
                )
            }
            // Was a second, near-identical list of every key: it is one fact
            // about this key, so it belongs next to the key.
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.wk_key_pinned), Modifier.weight(1f))
                Switch(pinned, onCheckedChange = {
                    pinned = it
                    val old = readEntries(prefs, Target.PINNED)
                    val new = if (old.any { e -> e.first == key }) old.map { e -> if (e.first == key) e.first to it else e }
                        else old + (key to it)
                    prefs.edit {
                        putString(Target.PINNED.pref, new.joinToString(Separators.ENTRY) { e -> e.first.name + Separators.KV + e.second })
                    }
                })
            }
            Column(Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.wk_key_lights_up), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(key.lightsUp()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                if (readCustomKeyCodes(prefs).containsKey(key))
                    TextButton(onClick = {
                        val codes = readCustomKeyCodes(prefs)
                        codes.remove(key)
                        writeCustomKeyCodes(prefs, codes)
                        code = TextFieldValue(getCodeForToolbarKey(key).toString())
                        longPressCode = TextFieldValue(getCodeForToolbarKeyLongClick(key).toString())
                    }) { Text(stringResource(R.string.button_default)) }
                if (onStrip)
                    TextButton(onClick = { saveCodes(); onTakeOff() }) {
                        Text(stringResource(R.string.wk_key_take_off))
                    }
                TextButton(onClick = { saveCodes(); onDismissRequest() }) {
                    Text(stringResource(R.string.dialog_close))
                }
            }
        }
    }
}

@Composable
private fun KeyChip(key: ToolbarKey, modifier: Modifier = Modifier, locked: Boolean = false) {
    val ctx = LocalContext.current
    val iconId = KeyboardIconsSet.iconIdsOfStyle(
        ctx.prefs().getString(Settings.PREF_ICON_STYLE, Defaults.PREF_ICON_STYLE(ctx.prefs()))
    )[key.name.lowercase(Locale.US)]
    // The chip is a picture of a key with no label beside it, so its description
    // is the only name it has. ToolbarKey.name is the enum — SELECT_WORD — which
    // is the developer's name for it, not the one on the settings row above.
    val label = key.name.lowercase(Locale.US).getStringResourceOrName("", ctx)
    Box(
        modifier.size(CHIP)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (locked) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        if (iconId != null)
            Icon(
                painterResourceCompat(iconId, 30),
                null,
                Modifier.size(22.dp),
                tint = if (locked) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
        else
            Text(label.take(2), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(6.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private enum class Group(val label: Int) {
    WAVEKEY(R.string.wk_group_wavekey),
    EDITING(R.string.wk_group_editing),
    CURSOR(R.string.wk_group_cursor),
    SYSTEM(R.string.wk_group_system),
}

private fun ToolbarKey.group() = when (this) {
    ToolbarKey.VOICE, ToolbarKey.AI_FIX, ToolbarKey.ASR_ENGINE, ToolbarKey.RESIZE, ToolbarKey.SWITCH_KEYBOARD -> Group.WAVEKEY
    ToolbarKey.LEFT, ToolbarKey.RIGHT, ToolbarKey.UP, ToolbarKey.DOWN, ToolbarKey.WORD_LEFT, ToolbarKey.WORD_RIGHT,
    ToolbarKey.PAGE_UP, ToolbarKey.PAGE_DOWN, ToolbarKey.FULL_LEFT, ToolbarKey.FULL_RIGHT,
    ToolbarKey.PAGE_START, ToolbarKey.PAGE_END -> Group.CURSOR
    ToolbarKey.SETTINGS, ToolbarKey.ONE_HANDED, ToolbarKey.FLOATING, ToolbarKey.SPLIT, ToolbarKey.INCOGNITO,
    ToolbarKey.AUTOCORRECT, ToolbarKey.BACKGROUND_GATHERING -> Group.SYSTEM
    else -> Group.EDITING
}

/** The fork's own keys need saying; the rest are named after what they already do. */
private fun ToolbarKey.about() = when (this) {
    ToolbarKey.AI_FIX -> R.string.wk_key_about_ai_fix
    ToolbarKey.ASR_ENGINE -> R.string.wk_key_about_asr_engine
    ToolbarKey.RESIZE -> R.string.wk_key_about_resize
    else -> null
}

private fun ToolbarKey.lightsUp() = when (this) {
    ToolbarKey.INCOGNITO -> R.string.wk_lights_incognito
    ToolbarKey.ONE_HANDED -> R.string.wk_lights_one_handed
    ToolbarKey.SPLIT -> R.string.wk_lights_split
    ToolbarKey.AUTOCORRECT -> R.string.wk_lights_autocorrect
    ToolbarKey.BACKGROUND_GATHERING -> R.string.wk_lights_gathering
    ToolbarKey.ASR_ENGINE -> R.string.wk_lights_asr
    ToolbarKey.AI_FIX -> R.string.wk_lights_ai_fix
    ToolbarKey.RESIZE -> R.string.wk_lights_resize
    else -> R.string.wk_lights_none
}

private enum class Target(val pref: String, val default: String, val label: Int) {
    MAIN(Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref, R.string.wk_target_main),
    PINNED(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref, R.string.wk_target_pinned),
    CLIPBOARD(Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref, R.string.wk_target_clipboard),
}

private fun countOf(prefs: android.content.SharedPreferences, target: Target) =
    readEntries(prefs, target).count { it.second }

private fun readEntries(prefs: android.content.SharedPreferences, target: Target): List<Pair<ToolbarKey, Boolean>> =
    prefs.getString(target.pref, target.default)!!
        .split(Separators.ENTRY)
        .mapNotNull {
            val split = it.split(Separators.KV)
            val key = runCatching { ToolbarKey.valueOf(split.first()) }.getOrNull() ?: return@mapNotNull null
            key to (split.last() == "true")
        }
        // The strip owns the mic and AI fix; they are not choices here.
        .filterNot { it.first in hiddenToolbarKeys }

/**
 * Moves the key at [from] in the enabled order to [to], leaving the switched-off
 * entries where they are — they carry no order the user can see.
 */
private fun move(
    entries: List<Pair<ToolbarKey, Boolean>>,
    enabled: List<ToolbarKey>,
    from: Int,
    to: Int,
): List<Pair<ToolbarKey, Boolean>> {
    if (from !in enabled.indices || to !in enabled.indices) return entries
    val newOrder = enabled.toMutableList().apply { add(to, removeAt(from)) }
    var i = 0
    return entries.map { if (it.second) newOrder[i++] to true else it }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface { ToolbarKeysScreen {} }
    }
}
