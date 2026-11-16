/*
 * Spektrafilm for Android — Lightroom-style export options sheet (§6a/§6b). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A format-aware export bottom sheet modeled on Lightroom mobile's export UI (RE'd from lrmobile:
 * format → format-specific options → dimensions → colour → naming → metadata), replacing the old
 * "export with whatever's in Settings" flow. UI only — the chosen [ExportOptions] drive the
 * post-engine encode in MainActivity, so there is no engine param and no parity impact.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spectrafilm.engine.ColorSpace

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    options: ExportOptions,
    onOptionsChange: (ExportOptions) -> Unit,
    colorSpace: ColorSpace,
    onColorSpaceChange: (ColorSpace) -> Unit,
    cctf: Boolean,
    onCctfChange: (Boolean) -> Unit,
    keepGps: Boolean,
    onKeepGpsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    // Dropdown's `display` is a plain (non-composable) lambda, so stringResource cannot go inside
    // it. LocalResources is the lint-approved read and, unlike LocalContext.current.getString,
    // recomposes when the configuration changes (locale, font scale).
    val resources = LocalResources.current
    // The custom-size field is edited as text, not digits: the old `filter(Char::isDigit)`
    // silently ate typos and the user never saw why. remembered across recomposition and
    // configuration changes so typing is never reset; the numeric ExportOptions field is only
    // updated from a valid parse (0 = empty = original size).
    var customEdgeText by rememberSaveable {
        mutableStateOf(if (options.customLongEdge <= 0) "" else options.customLongEdge.toString())
    }
    val customEdge = validateCustomLongEdge(customEdgeText)
    val customEdgeRes = when (customEdge) {
        is CustomEdgeValidation.NonNumeric -> R.string.tool_export_custom_invalid_number
        is CustomEdgeValidation.OutOfRange -> R.string.tool_export_custom_out_of_range
        else -> null
    }
    // The preview uses the same sanitiser as the export path. Never show a name that the
    // export would not actually use: null means "no usable characters", and the sheet says so
    // instead of rendering a blank or emoji filename.
    val cleanedBaseName = sanitizeExportBaseName(options.customName)
    val cleanedFileName = cleanedBaseName?.let { base ->
        val sceneSuffix = if (options.format == ExportFormat.SCENE_LINEAR_TIFF) "_scene-linear" else ""
        "$base$sceneSuffix.${options.format.ext}"
    }
    // The custom edge only constrains formats that go through the Bitmap resize. High-bit-depth
    // formats always export full-res, so a stale/invalid custom field must not disable them.
    val customEdgeApplicable = !options.format.isHighBitDepth() && options.size == ExportSize.CUSTOM
    val canExport = !customEdgeApplicable || customEdge.isValid
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The app runs edge-to-edge, and `adjustResize` alone does not lift Compose
                // content above the keyboard: without this the IME covers the file-name field
                // -- the one control in the app a user must type into to name an export.
                // Order matters: imePadding has to come BEFORE verticalScroll, or the scroll
                // viewport is sized without the inset and the field stays underneath.
                // ModalBottomSheet already handles the navigation-bar inset itself.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.tool_export_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            val isSceneLinear = options.format == ExportFormat.SCENE_LINEAR_TIFF

            // --- Format (+ format-specific quality) ---
            Dropdown(
                label = stringResource(R.string.tool_export_format),
                selected = options.format,
                options = ExportFormat.entries.toList(),
                display = { resources.getString(it.labelRes) },
                onSelect = { onOptionsChange(options.copy(format = it)) },
            )
            if (options.format == ExportFormat.JPEG || options.format == ExportFormat.ULTRA_HDR) {
                IntSlider(
                    label = stringResource(R.string.tool_export_quality),
                    value = options.jpegQuality,
                    range = 10..100,
                    default = ExportDefaults.JPEG_QUALITY,
                    onValueChange = { onOptionsChange(options.copy(jpegQuality = it)) },
                )
            }
            if (isSceneLinear) {
                Text(
                    stringResource(R.string.tool_export_scene_linear_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // --- Dimensions (post-render downscale, like Lightroom) ---
            Text(
                stringResource(R.string.tool_export_size_heading),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (options.format.isHighBitDepth()) {
                Text(
                    stringResource(R.string.tool_export_high_bit_depth_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Dropdown(
                    label = stringResource(R.string.tool_export_dimensions),
                    selected = options.size,
                    options = ExportSize.entries.toList(),
                    display = { resources.getString(it.labelRes) },
                    onSelect = { onOptionsChange(options.copy(size = it)) },
                )
                if (options.size == ExportSize.CUSTOM) {
                    OutlinedTextField(
                        value = customEdgeText,
                        onValueChange = { raw ->
                            customEdgeText = raw
                            // Keep the model in step with the field without rewriting the raw
                            // text. An empty or invalid field maps to 0 ("original size"), so a
                            // later export can never reuse the previous valid number; export is
                            // disabled below while the field is invalid.
                            onOptionsChange(
                                options.copy(
                                    customLongEdge = validateCustomLongEdge(raw).valueOrNull ?: 0,
                                ),
                            )
                        },
                        label = { Text(stringResource(R.string.tool_export_long_edge)) },
                        singleLine = true,
                        isError = customEdgeRes != null,
                        supportingText = {
                            Text(
                                text = customEdgeRes?.let {
                                    stringResource(
                                        it,
                                        ExportOptions.MIN_CUSTOM_EDGE,
                                        ExportOptions.MAX_CUSTOM_EDGE,
                                    )
                                } ?: stringResource(
                                    R.string.tool_export_custom_clamp,
                                    ExportOptions.MIN_CUSTOM_EDGE,
                                    ExportOptions.MAX_CUSTOM_EDGE,
                                ),
                                color = if (customEdgeRes != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (!isSceneLinear) {
                HorizontalDivider()

                // --- Colour ---
                Dropdown(
                    label = stringResource(R.string.tool_export_color_space),
                    selected = colorSpace,
                    options = ColorSpace.entries.toList(),
                    display = { resources.getString(it.uiLabelRes()) },
                    onSelect = onColorSpaceChange,
                )
                SwitchRow(
                    label = stringResource(R.string.tool_export_cctf),
                    checked = cctf,
                    onCheckedChange = onCctfChange,
                    tooltip = stringResource(R.string.tool_export_cctf_help),
                )
            }

            HorizontalDivider()

            // --- Naming + metadata ---
            OutlinedTextField(
                value = options.customName,
                onValueChange = { onOptionsChange(options.copy(customName = it)) },
                label = { Text(stringResource(R.string.tool_export_file_name)) },
                placeholder = { Text(stringResource(R.string.tool_export_file_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (cleanedFileName != null) {
                Text(
                    stringResource(R.string.tool_export_file_name_preview, cleanedFileName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (options.customName.isNotBlank()) {
                Text(
                    stringResource(R.string.tool_export_file_name_auto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isSceneLinear) {
                SwitchRow(
                    label = stringResource(R.string.tool_export_include_gps),
                    checked = keepGps,
                    onCheckedChange = onKeepGpsChange,
                    tooltip = stringResource(R.string.tool_export_include_gps_help),
                )
            }

            // --- Actions ---
            // The whole sheet scrolls, so at 200% font or on a short window these actions stay
            // reachable instead of being clipped by a fixed height. Each is at least 48 dp.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.tool_cancel))
                }
                Button(
                    onClick = export@{
                        if (!canExport) return@export
                        // Commit the currently typed value before leaving the sheet. This is
                        // synchronous state, so MainActivity's onExport reads the new edge, not
                        // the last one that happened to trigger a recomposition.
                        if (customEdgeApplicable) {
                            val edgeValue = customEdge.valueOrNull ?: 0
                            if (edgeValue != options.customLongEdge) {
                                onOptionsChange(options.copy(customLongEdge = edgeValue))
                            }
                        }
                        onExport()
                    },
                    enabled = canExport,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.tool_export_action))
                }
            }

            Text(
                stringResource(R.string.tool_export_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

