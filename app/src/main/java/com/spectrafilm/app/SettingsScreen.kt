/*
 * Spektrafilm for Android — Settings screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * App-level settings persisted via AppSettings (SharedPreferences): default output
 * color space, preview resolution, default film/print profiles, export format +
 * quality, theme, dynamic colour, a "Show onboarding again" action, a "Report an issue"
 * entry (and a "View issues" link), and an embedded collapsible About card. Edits write
 * straight back to the passed AppSettings and mirror into local Compose state so the
 * controls update live. Theme (via onThemeChanged) and language (via onLanguageChanged)
 * are re-applied immediately; render defaults apply on the next launch, and export
 * defaults apply on the next return to the editor.
 *
 * The host navigation already shows the screen title, so this screen starts with its first
 * group. Groups are flat, always-expanded warm surfaces with a heading and a note next to
 * the controls they describe — not fake accordions. Every primary row and button keeps a
 * real 48 dp target and stacks vertically so 200% text cannot truncate a link.
 *
 * This screen performs no network I/O. The app has no updater or HTTP client; the GitHub,
 * source, issue and licence entries are ACTION_VIEW intents handed to the user's browser.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spectrafilm.engine.ColorSpace

/**
 * The Settings UI. [settings] is the live store; [onThemeChanged] lets the host re-apply
 * the theme immediately. [filmGroups]/[printGroups] are the catalog-grouped profile
 * options (may be empty before the engine is ready); [onShowOnboarding] re-launches the
 * welcome flow.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    filmGroups: List<DropdownGroup>,
    printGroups: List<DropdownGroup>,
    onThemeChanged: (ThemeMode) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onLanguageChanged: (AppLanguage) -> Unit,
    onShowOnboarding: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
) {
    val ctx = LocalContext.current
    // Evaluated during composition / captured by it: LocalResources recomposes on a
    // configuration change where LocalContext.current.getString does not.
    val res = LocalResources.current

    // Local mirrors of the persisted values so controls update without a recomposition key.
    var theme by remember { mutableStateOf(settings.theme) }
    var language by remember { mutableStateOf(settings.language) }
    var dynamicColor by remember { mutableStateOf(settings.dynamicColor) }
    var outputCs by remember { mutableStateOf(settings.defaultOutputColorSpace) }
    var previewSize by remember { mutableIntStateOf(settings.previewMaxSize) }
    var draftSize by remember { mutableIntStateOf(settings.draftRenderMaxPx) }
    var film by remember { mutableStateOf(settings.defaultFilmProfile) }
    var print by remember { mutableStateOf(settings.defaultPrintProfile) }
    var format by remember { mutableStateOf(settings.exportFormat) }
    var quality by remember { mutableIntStateOf(settings.exportQuality) }
    var keepGps by remember { mutableStateOf(settings.exportKeepGps) }

    val opensInBrowser = stringResource(R.string.screen_opens_in_browser)
    // Label only: each link button keeps its own click action (null action merges).
    val linkSemantics = Modifier.semantics { onClick(label = opensInBrowser, action = null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // --- Appearance ---
        SettingsGroup(stringResource(R.string.screen_settings_appearance)) {
            Dropdown(
                label = stringResource(R.string.screen_settings_theme),
                selected = theme,
                options = ThemeMode.entries.toList(),
                display = { res.getString(it.labelRes) },
                onSelect = { theme = it; settings.theme = it; onThemeChanged(it) },
            )
            Dropdown(
                label = stringResource(R.string.screen_settings_language),
                selected = language,
                options = AppLanguage.entries.toList(),
                display = { res.getString(it.labelRes) },
                onSelect = { language = it; settings.language = it; onLanguageChanged(it) },
            )
            Text(
                stringResource(R.string.screen_settings_language_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Only offered where the platform can actually do it (API 31+). Showing a switch that
            // silently does nothing is worse than not showing one.
            if (dynamicColorSupported) {
                SettingToggleRow(
                    title = stringResource(R.string.screen_settings_dynamic_color),
                    note = stringResource(R.string.screen_settings_dynamic_color_note),
                    checked = dynamicColor,
                    onCheckedChange = {
                        dynamicColor = it
                        settings.dynamicColor = it
                        onDynamicColorChanged(it)
                    },
                )
            }
        }

        // --- Defaults ---
        SettingsGroup(
            title = stringResource(R.string.screen_settings_render_defaults),
            note = stringResource(R.string.screen_settings_render_defaults_note),
        ) {
            Dropdown(
                label = stringResource(R.string.screen_settings_default_output_cs),
                selected = outputCs,
                options = ColorSpace.entries.toList(),
                display = { res.getString(it.uiLabelRes()) },
                onSelect = { outputCs = it; settings.defaultOutputColorSpace = it },
            )
            IntSlider(
                label = stringResource(R.string.screen_settings_preview_max_size),
                value = previewSize,
                range = 128..1024,
                onValueChange = { previewSize = it; settings.previewMaxSize = it },
                tooltip = stringResource(R.string.screen_settings_preview_max_size_tip),
            )
            IntSlider(
                label = stringResource(R.string.screen_settings_draft_size),
                value = draftSize,
                range = 128..512,
                onValueChange = { draftSize = it; settings.draftRenderMaxPx = it },
                tooltip = stringResource(R.string.screen_settings_draft_size_tip),
            )
            if (filmGroups.isNotEmpty()) {
                GroupedDropdown(
                    label = stringResource(R.string.screen_settings_default_film),
                    selectedId = film.ifEmpty { filmGroups.firstOrNull()?.options?.firstOrNull()?.id ?: "" },
                    groups = filmGroups,
                    onSelect = { film = it; settings.defaultFilmProfile = it },
                )
            }
            if (printGroups.isNotEmpty()) {
                GroupedDropdown(
                    label = stringResource(R.string.screen_settings_default_print),
                    selectedId = print.ifEmpty { printGroups.firstOrNull()?.options?.firstOrNull()?.id ?: "" },
                    groups = printGroups,
                    onSelect = { print = it; settings.defaultPrintProfile = it },
                )
            }
        }

        // --- Export ---
        SettingsGroup(
            title = stringResource(R.string.screen_settings_export),
            note = stringResource(R.string.screen_settings_export_note),
        ) {
            Dropdown(
                label = stringResource(R.string.screen_settings_export_format),
                selected = format,
                options = ExportFormat.entries.toList(),
                display = { res.getString(it.labelRes) },
                onSelect = { format = it; settings.exportFormat = it },
            )
            if (format == ExportFormat.JPEG || format == ExportFormat.ULTRA_HDR) {
                IntSlider(
                    label = stringResource(R.string.screen_settings_jpeg_quality),
                    value = quality,
                    range = 1..100,
                    onValueChange = { quality = it; settings.exportQuality = it },
                )
            }
            SettingToggleRow(
                title = stringResource(R.string.screen_settings_keep_gps),
                note = stringResource(R.string.screen_settings_keep_gps_note),
                checked = keepGps,
                onCheckedChange = { keepGps = it; settings.exportKeepGps = it },
            )
        }

        // The three GPU toggles that stood here are gone: the engine's GPU route
        // is now how the app renders, gated per device by its own self-check with
        // automatic CPU fallback, so there is nothing left for a user to choose.
        // The card went with them rather than standing empty.

        // --- Diagnostics ---
        // There is deliberately no update card: this Chinese edition performs no in-app
        // network requests, so an updater button would have nothing it is allowed to call.
        // Release announcements remain reachable through the browser links under Help.
        SettingsGroup(stringResource(R.string.screen_settings_diagnostics_heading)) {
            OutlinedButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.screen_settings_diagnostics_logs))
            }
        }

        // --- Help / feedback ---
        SettingsGroup(stringResource(R.string.screen_settings_help_heading)) {
            Button(
                onClick = { Links.open(ctx, Links.NEW_ISSUE) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(linkSemantics),
            ) { Text(stringResource(R.string.screen_report_issue)) }
            // Stacked, not two weight(1f) cells: at 200% text a side-by-side pair truncates.
            OutlinedButton(
                onClick = { Links.open(ctx, Links.ISSUES) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(linkSemantics),
            ) { Text(stringResource(R.string.screen_settings_view_issues)) }
            OutlinedButton(
                onClick = { Links.open(ctx, Links.SOURCE) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(linkSemantics),
            ) { Text(stringResource(R.string.screen_settings_source)) }
            OutlinedButton(
                onClick = onShowOnboarding,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.screen_settings_show_onboarding)) }
        }

        // --- About (genuinely collapsible; it keeps its own SectionCard) ---
        AboutCard()
    }
}

/**
 * A flat, always-expanded settings group. The title is the group heading; [note] sits beside the
 * group so timing semantics ("next launch", "next editor visit") are not separated from the
 * controls they describe. The low-level surface keeps the screen from becoming a stack of
 * floating cards.
 */
@Composable
private fun SettingsGroup(
    title: String,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (note != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

/**
 * A labelled switch row. The whole row is the toggle (`Role.Switch`), so TalkBack sees one
 * node — "title, note, switch, on/off" — and the tap target is the full >= 48 dp row, not the
 * thumb.
 */
@Composable
private fun SettingToggleRow(
    title: String,
    note: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row owns the toggle; a null callback keeps the Switch from adding a second node.
        Switch(checked = checked, onCheckedChange = null)
    }
}
