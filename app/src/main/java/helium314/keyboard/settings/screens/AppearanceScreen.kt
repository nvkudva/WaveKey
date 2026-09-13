// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.settings.findIndexOfDefaultSetting
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.PreferenceGroup
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.KeyboardPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.StylePreview
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.dialogs.CustomizeIconsDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.BackgroundImagePref
import helium314.keyboard.settings.preferences.CustomFontPreference
import helium314.keyboard.settings.preferences.KeyboardScalePreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.latin.utils.previewDark
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog

/**
 * WaveKey: Look & feel, rebuilt on the mock's "one screen, board on top".
 *
 * The board is pinned above the settings instead of parked in a footer nobody
 * scrolls to, so every slider is judged against the thing it changes. Three
 * theme rows become one control plus one row; four key-shape rows become one
 * door; and the six size dialogs that each re-asked which orientation you meant
 * become one chip row over inline sliders.
 */
@Composable
fun AppearanceScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val canDayNight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    val autoDayNight = canDayNight && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
    // Which half of the pair the Theme row edits. Starts on the one in use.
    var editNight by rememberSaveable { mutableStateOf(ResourceUtils.isNight(ctx.resources) && autoDayNight) }
    if (!autoDayNight && editNight) editNight = false

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_door_look),
        settings = emptyList(),
        content = {
            Column(Modifier.fillMaxSize()) {
                KeyboardPreview()
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
                ) {
                    PreferenceCategory(stringResource(R.string.settings_screen_theme))
                    PreferenceGroup {
                        DayNightChips(canDayNight, autoDayNight, editNight) { editNight = it }
                        ThemeRow(editNight)
                        SettingsActivity.settingsContainer[Settings.PREF_NAVBAR_COLOR]?.Preference()
                        Preference(
                            name = stringResource(R.string.wk_look_key_style),
                            description = stringResource(R.string.wk_look_key_style_summary),
                            onClick = { SettingsDestination.navigateTo(SettingsDestination.KeyStyle) }
                        ) { NextScreenIcon() }
                    }

                    PreferenceCategory(stringResource(R.string.wk_look_background))
                    PreferenceGroup {
                        SettingsActivity.settingsContainer[SettingsWithoutKey.BACKGROUND_IMAGE]?.Preference()
                        SettingsActivity.settingsContainer[SettingsWithoutKey.BACKGROUND_IMAGE_LANDSCAPE]?.Preference()
                    }

                    PreferenceCategory(stringResource(R.string.wk_category_size))
                    SizeAndSpacing()

                    PreferenceCategory(stringResource(R.string.wk_category_text))
                    PreferenceGroup {
                        SettingsActivity.settingsContainer[SettingsWithoutKey.CUSTOM_FONT]?.Preference()
                        SettingsActivity.settingsContainer[Settings.PREF_FONT_SCALE]?.Preference()
                        // Stays mounted when hints are off: a row that reads
                        // unavailable is easier to find again than one that vanished.
                        Dimmed(
                            prefs.getBoolean(Settings.PREF_SHOW_HINTS, Defaults.PREF_SHOW_HINTS),
                            stringResource(R.string.wk_needs_hints),
                        ) {
                            SettingsActivity.settingsContainer[Settings.PREF_HINT_FONT_SCALE]?.Preference()
                        }
                        SettingsActivity.settingsContainer[Settings.PREF_SPACE_BAR_TEXT]?.Preference()
                    }

                    PreferenceGroup(Modifier.padding(top = 16.dp)) {
                        Preference(
                            name = stringResource(R.string.wk_look_emoji),
                            description = stringResource(R.string.wk_look_emoji_summary),
                            onClick = { SettingsDestination.navigateTo(SettingsDestination.Emoji) }
                        ) { NextScreenIcon() }
                    }
                }
            }
        }
    )
}

/** Auto pairs a day and a night theme; Day and Night say which of the pair the Theme row edits. */
@Composable
private fun DayNightChips(canDayNight: Boolean, auto: Boolean, editNight: Boolean, onEditNight: (Boolean) -> Unit) {
    if (!canDayNight) return
    val prefs = LocalContext.current.prefs()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = auto,
            onClick = {
                prefs.edit { putBoolean(Settings.PREF_THEME_DAY_NIGHT, !auto) }
                if (auto) onEditNight(false)
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            label = { Text(stringResource(R.string.wk_look_auto)) }
        )
        FilterChip(
            selected = auto && !editNight,
            enabled = auto,
            onClick = { onEditNight(false) },
            label = { Text(stringResource(R.string.wk_day)) }
        )
        FilterChip(
            selected = auto && editNight,
            enabled = auto,
            onClick = { onEditNight(true) },
            label = { Text(stringResource(R.string.wk_night)) }
        )
    }
}

/** One row for what used to be Colors and Colors (night), following the chips. */
@Composable
private fun ThemeRow(editNight: Boolean) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val name = (if (editNight) prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)
        else prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS))!!
    Preference(
        name = stringResource(R.string.wk_look_theme),
        description = name.getStringResourceOrName("theme_name_", ctx),
        onClick = {
            SettingsDestination.navigateTo(
                if (editNight) SettingsDestination.ThemePickerNight else SettingsDestination.ThemePicker
            )
        }
    ) { NextScreenIcon() }
}

/** A row that is unavailable right now: shown, but visibly not in play. */
@Composable
fun Dimmed(enabled: Boolean, reason: String? = null, content: @Composable () -> Unit) {
    if (enabled) {
        Box { content() }
        return
    }
    // A row at 38% alpha that still answers a tap is the worst of both: it looks
    // unavailable and behaves available. Swallow the gesture, tell TalkBack the
    // control is disabled, and say why — a reason is the only thing that makes a
    // dimmed row better than a missing one, which is why it is kept mounted.
    Column {
        Box(
            Modifier
                .alpha(0.38f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes
                                .forEach { it.consume() }
                        }
                    }
                }
                .semantics(mergeDescendants = true) {
                    disabled()
                    if (reason != null) stateDescription = reason
                },
        ) { content() }
        if (reason != null) {
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}

/** Portrait, landscape and — on a foldable — the two folded variants. */
private enum class Orientation(val landscape: Boolean, val folded: Boolean) {
    PORTRAIT(false, false), LANDSCAPE(true, false), FOLDED(false, true), FOLDED_LANDSCAPE(true, true);

    val splitKey get() = if (landscape)
        (if (folded) Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE else Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE)
    else (if (folded) Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED else Settings.PREF_ENABLE_SPLIT_KEYBOARD)
}

/**
 * The six size dialogs, inlined. Upstream asked which orientation you meant once
 * per setting, inside each dialog; here the question is asked once, at the top,
 * and every slider below answers for that orientation.
 */
@Composable
private fun SizeAndSpacing() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    // LocalConfiguration, not ctx.resources.configuration: the context's copy
    // does not recompose, so a rotation left this screen editing the sizes of
    // the orientation the user had just left.
    val configuration = LocalConfiguration.current
    val orientations = Orientation.entries.filter { FoldableUtils.isFoldable || !it.folded }
    val here = Orientation.entries.first {
        it.landscape == (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) &&
                it.folded == (FoldableUtils.isFoldable && FoldableUtils.isFolded)
    }
    var orientation by rememberSaveable { mutableStateOf(if (here in orientations) here else Orientation.PORTRAIT) }
    val index = findIndexOfDefaultSetting(orientation.landscape, orientation.folded)
    val split = prefs.getBoolean(orientation.splitKey, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
    val bordered = prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS)

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        orientations.forEach { o ->
            FilterChip(
                selected = o == orientation,
                onClick = { orientation = o },
                label = {
                    Text(when (o) {
                        Orientation.PORTRAIT -> stringResource(R.string.wk_look_portrait)
                        Orientation.LANDSCAPE -> stringResource(R.string.landscape)
                        Orientation.FOLDED -> stringResource(R.string.folded)
                        Orientation.FOLDED_LANDSCAPE -> stringResource(R.string.wk_look_folded_landscape)
                    })
                }
            )
        }
    }
    PreferenceGroup {
        ScaleSlider(stringResource(R.string.prefs_keyboard_height_scale), Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX,
            2, index, Defaults.PREF_KEYBOARD_HEIGHT_SCALE[index], 0.3f..1.5f)
        ScaleSlider(stringResource(R.string.prefs_bottom_row_scale), Settings.PREF_BOTTOM_ROW_SCALE_PREFIX,
            2, index, Defaults.PREF_BOTTOM_ROW_SCALE[index], 0.5f..2f)
        // Side padding is the one scale that also depends on split, so its key
        // carries the split state of the orientation chosen above.
        ScaleSlider(stringResource(R.string.prefs_side_padding_scale), Settings.PREF_SIDE_PADDING_SCALE_PREFIX,
            3, findIndexOfDefaultSetting(orientation.landscape, split, orientation.folded),
            Defaults.PREF_SIDE_PADDING_SCALE[findIndexOfDefaultSetting(orientation.landscape, split, orientation.folded)], 0f..3f)
        ScaleSlider(stringResource(R.string.prefs_bottom_padding_scale), Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX,
            2, index, Defaults.PREF_BOTTOM_PADDING_SCALE[index], 0f..5f)
        Dimmed(bordered, stringResource(R.string.wk_needs_borders)) {
            ScaleSlider(stringResource(R.string.prefs_key_gap_scale), Settings.PREF_KEY_GAP_SCALE_PREFIX,
                2, index, Defaults.PREF_KEY_GAP_SCALE[index], 0.5f..2.5f, enabled = bordered)
        }
        SwitchPreference(
            name = stringResource(R.string.wk_look_split_this),
            key = orientation.splitKey,
            default = Defaults.PREF_ENABLE_SPLIT_KEYBOARD,
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
        Dimmed(split, stringResource(R.string.wk_needs_split)) {
            ScaleSlider(stringResource(R.string.wk_split_spacer), Settings.PREF_SPLIT_SPACER_SCALE_PREFIX,
                2, index, Defaults.PREF_SPLIT_SPACER_SCALE[index], 0.5f..2f, enabled = split)
        }
    }
}

/** One scale pref, for one orientation, shown as a slider rather than behind a dialog. */
@Composable
private fun ScaleSlider(
    name: String,
    baseKey: String,
    dimensionCount: Int,
    index: Int,
    default: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
) {
    val prefs = LocalContext.current.prefs()
    val key = createPrefKeyForBooleanSettings(baseKey, index, dimensionCount)
    var position by remember(key) { mutableFloatStateOf(prefs.getFloat(key, default)) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text("${(100 * position).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = position,
            enabled = enabled,
            onValueChange = { position = it },
            onValueChangeFinished = {
                if (position == default) prefs.edit { remove(key) } else prefs.edit { putFloat(key, position) }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            valueRange = range,
        )
    }
}

fun createAppearanceSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_THEME_STYLE, R.string.theme_style) { setting ->
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        val items = KeyboardTheme.STYLES.map {
            it.getStringResourceOrName("style_name_", ctx) to it
        }
        ListPreference(
            setting,
            items,
            Defaults.PREF_THEME_STYLE,
            itemPreview = { StylePreview(it) }
        ) {
            if (it != KeyboardTheme.STYLE_HOLO) {
                if (prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == KeyboardTheme.THEME_HOLO_WHITE)
                    prefs.edit { remove(Settings.PREF_THEME_COLORS) }
                if (prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT) == KeyboardTheme.THEME_HOLO_WHITE)
                    prefs.edit { remove(Settings.PREF_THEME_COLORS_NIGHT) }
            }
            KeyboardIconsSet.needsReload = true // only relevant for Settings.PREF_CUSTOM_ICON_NAMES
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_ICON_STYLE, R.string.icon_style) { setting ->
        val ctx = LocalContext.current
        val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
        if ((b?.value ?: 0) < 0)
            Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
        val items = KeyboardTheme.ICON_STYLES.map { it.getStringResourceOrName("style_name_", ctx) to it }
        ListPreference(
            setting,
            items,
            Defaults.PREF_ICON_STYLE(ctx.prefs()),
            { ctx.prefs().edit { remove(Settings.PREF_ICON_STYLE) } },
            itemPreview = { StylePreview(it) }
        ) {
            KeyboardIconsSet.needsReload = true // only relevant for Settings.PREF_CUSTOM_ICON_NAMES
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_CUSTOM_ICON_NAMES, R.string.customize_icons) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            onClick = { showDialog = true }
        )
        if (showDialog) {
            KeyboardIconsSet.instance.loadIcons(LocalContext.current)
            CustomizeIconsDialog(setting.key) { showDialog = false }
        }
    },
    Setting(context, Settings.PREF_THEME_COLORS, R.string.theme_colors) { setting ->
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
        if ((b?.value ?: 0) < 0)
            Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
        Preference(
            name = setting.title,
            description = prefs.getString(setting.key, Defaults.PREF_THEME_COLORS)!!.getStringResourceOrName("theme_name_", ctx),
            onClick = { SettingsDestination.navigateTo(SettingsDestination.ThemePicker) }
        )
    },
    Setting(context, Settings.PREF_THEME_COLORS_NIGHT, R.string.theme_colors_night) { setting ->
        val ctx = LocalContext.current
        val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
        val prefs = ctx.prefs()
        if ((b?.value ?: 0) < 0)
            Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
        Preference(
            name = setting.title,
            description = prefs.getString(setting.key, Defaults.PREF_THEME_COLORS_NIGHT)!!.getStringResourceOrName("theme_name_", ctx),
            onClick = { SettingsDestination.navigateTo(SettingsDestination.ThemePickerNight) }
        )
    },
    Setting(context, Settings.PREF_THEME_KEY_BORDERS, R.string.key_borders) {
        SwitchPreference(it, Defaults.PREF_THEME_KEY_BORDERS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_THEME_DAY_NIGHT, R.string.day_night_mode, R.string.day_night_mode_summary) {
        SwitchPreference(it, Defaults.PREF_THEME_DAY_NIGHT) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_NAVBAR_COLOR, R.string.theme_navbar, R.string.day_night_mode_summary) {
        SwitchPreference(it, Defaults.PREF_NAVBAR_COLOR)
    },
    Setting(context, SettingsWithoutKey.BACKGROUND_IMAGE, R.string.customize_background_image) {
        BackgroundImagePref(it, false)
    },
    Setting(context, SettingsWithoutKey.BACKGROUND_IMAGE_LANDSCAPE,
        R.string.customize_background_image_landscape, R.string.summary_customize_background_image_landscape)
    {
        BackgroundImagePref(it, true)
    },
    Setting(context, Settings.PREF_ENABLE_SPLIT_KEYBOARD, R.string.enable_split_keyboard) {
        var show by remember { mutableStateOf(false) }
        val prefAndName = listOfNotNull(
            Settings.PREF_ENABLE_SPLIT_KEYBOARD to stringResource(R.string.button_default),
            Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE to stringResource(R.string.landscape),
            if (!FoldableUtils.isFoldable) null else
                Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED to stringResource(R.string.folded),
            if (!FoldableUtils.isFoldable) null else
                Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE to stringResource(R.string.folded) + " / " + stringResource(R.string.landscape)
        )
        Preference(
            name = stringResource(R.string.enable_split_keyboard),
            onClick = { show = true },
            description = prefAndName.filter { LocalContext.current.prefs().getBoolean(it.first, Defaults.PREF_ENABLE_SPLIT_KEYBOARD) }
                .joinToString(", ") { it.second }.takeIf { it.isNotEmpty() }
        )
        if (show) {
            ThreeButtonAlertDialog(
                onDismissRequest = { show = false },
                onConfirmed = {},
                confirmButtonText = null,
                cancelButtonText = stringResource(R.string.dialog_close),
                content = {
                    Column {
                        prefAndName.forEach {
                            SwitchPreference(name = it.second, key = it.first, default = Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
                        }
                    }
                }
            )
        }
    },
    Setting(context, Settings.PREF_SPLIT_SPACER_SCALE_PREFIX, R.string.wk_split_spacer) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_SPLIT_SPACER_SCALE,
            range = 0.5f..2f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_KEY_GAP_SCALE_PREFIX, R.string.prefs_key_gap_scale) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_KEY_GAP_SCALE,
            range = 0.5f..2.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, R.string.prefs_keyboard_height_scale) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_KEYBOARD_HEIGHT_SCALE,
            range = 0.3f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, R.string.prefs_bottom_row_scale) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_BOTTOM_ROW_SCALE,
            range = 0.5f..2f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, R.string.prefs_bottom_padding_scale) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_BOTTOM_PADDING_SCALE,
            range = 0f..5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SIDE_PADDING_SCALE_PREFIX, R.string.prefs_side_padding_scale) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.split), stringResource(R.string.folded)),
            defaults = Defaults.PREF_SIDE_PADDING_SCALE,
            range = 0f..3f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SPACE_BAR_TEXT, R.string.prefs_space_bar_text) {
        TextInputPreference(it, Defaults.PREF_SPACE_BAR_TEXT)
    },
    Setting(context, SettingsWithoutKey.CUSTOM_FONT, R.string.custom_font) {
        CustomFontPreference(it, Settings.getCustomFontFile(LocalContext.current), R.string.custom_font)
    },
    Setting(context, Settings.PREF_FONT_SCALE, R.string.prefs_font_scale) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_FONT_SCALE,
            range = 0.5f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_HINT_FONT_SCALE, R.string.prefs_hint_font_scale) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_HINT_FONT_SCALE,
            range = 0.5f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, SettingsWithoutKey.CUSTOM_EMOJI_FONT, R.string.custom_emoji_font) {
        CustomFontPreference(it, Settings.getCustomEmojiFontFile(LocalContext.current), R.string.custom_emoji_font)
    },
    Setting(context, Settings.PREF_EMOJI_FONT_SCALE, R.string.prefs_emoji_font_scale) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_EMOJI_FONT_SCALE,
            range = 0.5f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_EMOJI_KEY_FIT, R.string.prefs_emoji_key_fit) {
        SwitchPreference(it, Defaults.PREF_EMOJI_KEY_FIT) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_EMOJI_SKIN_TONE, R.string.prefs_emoji_skin_tone) { setting ->
        val items = listOf(
            stringResource(R.string.prefs_emoji_skin_tone_neutral) to "",
            "\uD83C\uDFFB" to "\uD83C\uDFFB",
            "\uD83C\uDFFC" to "\uD83C\uDFFC",
            "\uD83C\uDFFD" to "\uD83C\uDFFD",
            "\uD83C\uDFFE" to "\uD83C\uDFFE",
            "\uD83C\uDFFF" to "\uD83C\uDFFF"
        )
        ListPreference(setting, items, Defaults.PREF_EMOJI_SKIN_TONE) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            AppearanceScreen { }
        }
    }
}
