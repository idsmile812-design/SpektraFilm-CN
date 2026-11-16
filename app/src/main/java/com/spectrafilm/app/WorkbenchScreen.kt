/*
 * Spektrafilm for Android — single-page capture workbench. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt

// The workbench body is its own small instrument: near-black green, low-luminance grey-green
// lettering and one amber reference colour. These are deliberately fixed rather than pulled
// from the app colour scheme, so a light-system theme cannot turn the camera body into paper.
private val WorkbenchVoid = Color(0xFF050806)
private val WorkbenchBody = Color(0xFF0D1411)
private val WorkbenchPanel = Color(0xFF141D19)
private val WorkbenchFilmCard = Color(0xFF1A241F)
private val WorkbenchLine = Color(0xFF2B3A32)
private val WorkbenchLineBright = Color(0xFF40584B)
private val WorkbenchMuted = Color(0xFF93A69B)
private val WorkbenchWhite = Color(0xFFE7EEE9)
private val WorkbenchAmber = Color(0xFFE8A44F)
private val WorkbenchSage = Color(0xFF9DB5A5)
private val WorkbenchWarm = Color(0xFFD98F58)

private const val METER_STEP = 0.1f

private enum class MeterKind(val accent: Color) {
    EXPOSURE(WorkbenchAmber),
    GRAIN(WorkbenchSage),
    HALATION(WorkbenchWarm),
}

@Composable
internal fun WorkbenchScreen(
    selection: WorkbenchSelection,
    onSelectionChange: (WorkbenchSelection) -> Unit,
    presets: List<BuiltInPreset>,
    preview: Bitmap?,
    photoMode: Boolean,
    onApplyPreset: (BuiltInPreset) -> Unit,
    onOpenPhotoPicker: () -> Unit,
    onOpenRawPicker: () -> Unit,
    onOpenDarkroom: () -> Unit,
    onUsePhoto: (Uri, WorkbenchSelection) -> Unit,
) {
    val context = LocalContext.current
    val packagingByStock = remember(context) {
        runCatching { PackagingCatalog.load(context) }.getOrNull()
            .orEmpty().associateBy(PackagingEntry::stockId)
    }
    val selected = presets.firstOrNull { it.id == selection.presetId }
        ?: presets.firstOrNull()
    Surface(color = WorkbenchVoid, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val compact = maxHeight < 680.dp || LocalDensity.current.fontScale >= 1.3f
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            ) {
                WorkbenchTopPlate(
                    onOpenPhotoPicker = onOpenPhotoPicker,
                    onOpenRawPicker = onOpenRawPicker,
                    onOpenDarkroom = onOpenDarkroom,
                )
                Box(
                    (if (compact) {
                        Modifier.fillMaxWidth().height(360.dp)
                    } else {
                        Modifier.fillMaxWidth().weight(1f)
                    }).clip(androidx.compose.ui.graphics.RectangleShape),
                ) {
                    if (photoMode) {
                        PhotoStage(
                            preview = preview,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CameraWorkbench(
                            selection = selection,
                            onSelectionChange = onSelectionChange,
                            onOpenPhotoPicker = onOpenPhotoPicker,
                            onUsePhoto = onUsePhoto,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                CameraMeterDeck(
                    selection = selection,
                    onSelectionChange = onSelectionChange,
                    stacked = compact,
                )
                if (selected != null) {
                    LoadedFilmStrip(
                        name = BuiltInPresets.displayName(context, selected),
                        description = BuiltInPresets.displayDescription(context, selected),
                    )
                }
                FilmRail(
                    presets = presets,
                    packagingByStock = packagingByStock,
                    selectedId = selected?.id.orEmpty(),
                    onSelect = { preset ->
                        onSelectionChange(selection.copy(presetId = preset.id))
                        if (photoMode) onApplyPreset(preset)
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkbenchTopPlate(
    onOpenPhotoPicker: () -> Unit,
    onOpenRawPicker: () -> Unit,
    onOpenDarkroom: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(WorkbenchBody)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(22.dp).background(WorkbenchAmber))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.workbench_title),
                    color = WorkbenchWhite,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "SPEKTRAFILM",
                    color = WorkbenchMuted,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.6.sp,
                    maxLines = 1,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PlateIconButton(
                    icon = SpectraIcons.SourceImage,
                    description = stringResource(R.string.workbench_open_photo),
                    onClick = onOpenPhotoPicker,
                )
                PlateIconButton(
                    icon = SpectraIcons.ImportRaw,
                    description = stringResource(R.string.workbench_open_raw),
                    onClick = onOpenRawPicker,
                )
                PlateIconButton(
                    icon = SpectraIcons.Settings,
                    description = stringResource(R.string.workbench_darkroom),
                    onClick = onOpenDarkroom,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(WorkbenchLine))
    }
}

@Composable
private fun PlateIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(3.dp)
            .clip(shape)
            .background(WorkbenchPanel.copy(alpha = 0.55f))
            .border(1.dp, WorkbenchLine, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = WorkbenchWhite, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun PhotoStage(preview: Bitmap?, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = stringResource(R.string.workbench_photo_desc),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.width(28.dp).height(2.dp).background(WorkbenchAmber))
                Text(
                    stringResource(R.string.workbench_no_photo),
                    color = WorkbenchMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Camera meter deck
//
// Three compact body readouts, not three Material sliders. Each channel has its
// own engraved scale and needle language (amber EV pointer, grey-green grain
// pulses, warm halation bloom) while sharing one pointer/semantics contract.
// ---------------------------------------------------------------------------

@Composable
private fun CameraMeterDeck(
    selection: WorkbenchSelection,
    onSelectionChange: (WorkbenchSelection) -> Unit,
    stacked: Boolean,
) {
    val exposureLabel = stringResource(R.string.workbench_exposure)
    val grainLabel = stringResource(R.string.workbench_grain)
    val halationLabel = stringResource(R.string.workbench_halation)

    Column(Modifier.fillMaxWidth().background(WorkbenchBody)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(WorkbenchLine))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 7.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(2.dp).height(10.dp).background(WorkbenchAmber))
            Spacer(Modifier.width(7.dp))
            Text(
                stringResource(R.string.workbench_meter_title).uppercase(Locale.ROOT),
                color = WorkbenchMuted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "35 mm",
                color = WorkbenchMuted.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth()) {
                MeterChannel(
                    label = exposureLabel,
                    value = selection.exposureEv,
                    range = -2f..2f,
                    kind = MeterKind.EXPOSURE,
                    onChange = { onSelectionChange(selection.copy(exposureEv = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                MeterDivider(vertical = false)
                MeterChannel(
                    label = grainLabel,
                    value = selection.grain,
                    range = 0f..2f,
                    kind = MeterKind.GRAIN,
                    onChange = { onSelectionChange(selection.copy(grain = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                MeterDivider(vertical = false)
                MeterChannel(
                    label = halationLabel,
                    value = selection.halation,
                    range = 0f..2f,
                    kind = MeterKind.HALATION,
                    onChange = { onSelectionChange(selection.copy(halation = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MeterChannel(
                    label = exposureLabel,
                    value = selection.exposureEv,
                    range = -2f..2f,
                    kind = MeterKind.EXPOSURE,
                    onChange = { onSelectionChange(selection.copy(exposureEv = it)) },
                    modifier = Modifier.weight(1.15f),
                )
                MeterDivider(vertical = true)
                MeterChannel(
                    label = grainLabel,
                    value = selection.grain,
                    range = 0f..2f,
                    kind = MeterKind.GRAIN,
                    onChange = { onSelectionChange(selection.copy(grain = it)) },
                    modifier = Modifier.weight(1f),
                )
                MeterDivider(vertical = true)
                MeterChannel(
                    label = halationLabel,
                    value = selection.halation,
                    range = 0f..2f,
                    kind = MeterKind.HALATION,
                    onChange = { onSelectionChange(selection.copy(halation = it)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(WorkbenchLine))
    }
}

@Composable
private fun MeterDivider(vertical: Boolean) {
    if (vertical) {
        Box(Modifier.width(1.dp).height(48.dp).background(WorkbenchLine.copy(alpha = 0.8f)))
    } else {
        Box(Modifier.fillMaxWidth().height(1.dp).background(WorkbenchLine.copy(alpha = 0.8f)))
    }
}

@Composable
private fun MeterChannel(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    kind: MeterKind,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val valueText = meterText(kind, value)
    val steps = ((range.endInclusive - range.start) / METER_STEP).roundToInt() - 1
    Column(
        modifier
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value,
                    range = range,
                    steps = steps.coerceAtLeast(0),
                )
                setProgress { proposed ->
                    onChange(snapMeterValue(proposed, range))
                    true
                }
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = WorkbenchMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                color = kind.accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        MeterTrack(
            kind = kind,
            value = value,
            range = range,
            onChange = onChange,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

@Composable
private fun MeterTrack(
    kind: MeterKind,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnChange by rememberUpdatedState(onChange)
    Box(
        modifier
            .height(36.dp)
            .clipToBounds()
            .pointerInput(kind, range) {
                detectTapGestures { position ->
                    if (size.width > 0) {
                        latestOnChange(meterValueAt(position.x / size.width, range))
                    }
                }
            }
            .pointerInput(kind, range) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    if (size.width > 0) {
                        latestOnChange(meterValueAt(change.position.x / size.width, range))
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val span = range.endInclusive - range.start
            val fraction = if (span <= 0f) 0f
            else ((value - range.start) / span).coerceIn(0f, 1f)
            val inset = 2.dp.toPx()
            val usable = (size.width - inset * 2f).coerceAtLeast(1f)
            val needleX = inset + usable * fraction
            val baselineY = size.height * 0.70f
            val hair = 0.75.dp.toPx()

            drawLine(
                color = WorkbenchLine,
                start = Offset(inset, baselineY),
                end = Offset(size.width - inset, baselineY),
                strokeWidth = hair,
            )
            drawLine(
                color = kind.accent.copy(alpha = 0.42f),
                start = Offset(inset, baselineY + 2.dp.toPx()),
                end = Offset(needleX, baselineY + 2.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
            )

            val divisions = when (kind) {
                MeterKind.EXPOSURE -> 20
                MeterKind.GRAIN -> 16
                MeterKind.HALATION -> 12
            }
            for (index in 0..divisions) {
                val x = inset + usable * index / divisions
                val major = index == 0 || index == divisions ||
                    (kind == MeterKind.EXPOSURE && index == divisions / 2)
                val tick = if (major) 8.dp.toPx() else 4.dp.toPx()
                drawLine(
                    color = if (major) WorkbenchLineBright else WorkbenchLine,
                    start = Offset(x, baselineY - 2.dp.toPx()),
                    end = Offset(x, baselineY - 2.dp.toPx() - tick),
                    strokeWidth = hair,
                )
            }

            when (kind) {
                MeterKind.EXPOSURE -> {
                    val zeroX = inset + usable * ((0f - range.start) / span)
                    drawLine(
                        color = WorkbenchAmber.copy(alpha = 0.48f),
                        start = Offset(zeroX, baselineY - 9.dp.toPx()),
                        end = Offset(zeroX, baselineY + 2.dp.toPx()),
                        strokeWidth = hair,
                    )
                }
                MeterKind.GRAIN -> {
                    val grainAlpha = (0.12f + fraction * 0.5f).coerceIn(0f, 0.8f)
                    for (row in 0 until 3) {
                        for (column in 0 until 15) {
                            val x = inset + usable * (column + 0.5f) / 15f
                            val y = baselineY - 15.dp.toPx() + row * 4.dp.toPx()
                            drawCircle(
                                color = WorkbenchSage.copy(
                                    alpha = grainAlpha * (0.4f + row * 0.16f),
                                ),
                                radius = 0.7.dp.toPx(),
                                center = Offset(x, y),
                            )
                        }
                    }
                }
                MeterKind.HALATION -> {
                    val bloom = WorkbenchWarm.copy(alpha = 0.10f + 0.24f * fraction)
                    drawCircle(
                        color = bloom,
                        radius = 15.dp.toPx(),
                        center = Offset(needleX, baselineY - 5.dp.toPx()),
                    )
                    drawCircle(
                        color = bloom.copy(alpha = bloom.alpha * 0.5f),
                        radius = 23.dp.toPx(),
                        center = Offset(needleX, baselineY - 5.dp.toPx()),
                    )
                }
            }

            when (kind) {
                MeterKind.EXPOSURE -> {
                    drawLine(
                        color = WorkbenchAmber,
                        start = Offset(needleX, baselineY - 14.dp.toPx()),
                        end = Offset(needleX, baselineY + 5.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                    )
                    drawCircle(
                        color = WorkbenchAmber,
                        radius = 2.dp.toPx(),
                        center = Offset(needleX, baselineY - 15.dp.toPx()),
                    )
                }
                MeterKind.GRAIN -> {
                    val needleWidth = 5.dp.toPx()
                    val needleHeight = 17.dp.toPx()
                    drawRect(
                        color = WorkbenchSage,
                        topLeft = Offset(
                            needleX - needleWidth / 2f,
                            baselineY - needleHeight + 3.dp.toPx(),
                        ),
                        size = Size(needleWidth, needleHeight),
                    )
                    drawRect(
                        color = WorkbenchBody,
                        topLeft = Offset(
                            needleX - 0.75.dp.toPx(),
                            baselineY - needleHeight + 6.dp.toPx(),
                        ),
                        size = Size(1.5.dp.toPx(), needleHeight - 6.dp.toPx()),
                    )
                }
                MeterKind.HALATION -> {
                    drawLine(
                        color = WorkbenchWarm,
                        start = Offset(needleX, baselineY - 14.dp.toPx()),
                        end = Offset(needleX, baselineY + 5.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                    )
                    drawCircle(
                        color = WorkbenchWarm,
                        radius = 2.dp.toPx(),
                        center = Offset(needleX, baselineY - 15.dp.toPx()),
                    )
                }
            }
        }
    }
}

private fun meterText(kind: MeterKind, value: Float): String {
    val normalized = if (value == 0f) 0f else value
    return when (kind) {
        MeterKind.EXPOSURE -> String.format(Locale.getDefault(), "%+.1f", normalized)
        MeterKind.GRAIN, MeterKind.HALATION ->
            String.format(Locale.getDefault(), "%.1f", normalized)
    }
}

private fun meterValueAt(fraction: Float, range: ClosedFloatingPointRange<Float>): Float {
    val clamped = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val raw = range.start + (range.endInclusive - range.start) * clamped
    return snapMeterValue(raw, range)
}

private fun snapMeterValue(raw: Float, range: ClosedFloatingPointRange<Float>): Float {
    val snapped = (round(raw * 10f) / 10f).coerceIn(range.start, range.endInclusive)
    return if (snapped == 0f) 0f else snapped
}

// ---------------------------------------------------------------------------
// Film rail
//
// Each stock is a rectangular darkroom box: packaging art on top, a solid
// model-name label strip underneath. No ellipse, circle or generic card. When a
// stock has no packaging, the box becomes a full rectangular model label.
// ---------------------------------------------------------------------------

@Composable
private fun LoadedFilmStrip(name: String, description: String) {
    val shape = RoundedCornerShape(2.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(WorkbenchBody)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).height(30.dp).background(WorkbenchAmber))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = WorkbenchWhite,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = WorkbenchMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .clip(shape)
                .border(1.dp, WorkbenchLine, shape)
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                SpectraIcons.Simulation,
                contentDescription = null,
                tint = WorkbenchAmber,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "35 mm",
                color = WorkbenchMuted,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FilmRail(
    presets: List<BuiltInPreset>,
    packagingByStock: Map<String, PackagingEntry>,
    selectedId: String,
    onSelect: (BuiltInPreset) -> Unit,
) {
    val context = LocalContext.current
    val state = rememberLazyListState()
    LaunchedEffect(selectedId, presets) {
        val index = presets.indexOfFirst { it.id == selectedId }
        if (index >= 0) state.scrollToItem(index)
    }
    BoxWithConstraints(Modifier.fillMaxWidth().background(WorkbenchBody)) {
        val itemWidth = 120.dp
        val horizontal = ((maxWidth - itemWidth) / 2).coerceAtLeast(12.dp)
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth().heightIn(min = 132.dp),
            contentPadding = PaddingValues(horizontal = horizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(presets, key = { it.id }) { preset ->
                val isSelected = preset.id == selectedId
                FilmBox(
                    modelName = StockCatalog.displayName(context, preset.filmProfile),
                    entry = packagingByStock[preset.filmProfile],
                    selected = isSelected,
                    onClick = { onSelect(preset) },
                    modifier = Modifier.width(itemWidth),
                )
            }
        }
    }
}

@Composable
private fun FilmBox(
    modelName: String,
    entry: PackagingEntry?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .heightIn(min = 116.dp)
            .clip(shape)
            .background(WorkbenchFilmCard)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) WorkbenchAmber else WorkbenchLine,
                shape = shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = modelName
                this.selected = selected
            },
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (entry != null) {
                PackagingVisual(
                    entry = entry,
                    modelName = modelName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp)
                        .clearAndSetSemantics { },
                )
            } else {
                FilmBoxFallback(Modifier.fillMaxWidth().height(62.dp))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) WorkbenchAmber.copy(alpha = 0.13f) else WorkbenchPanel,
                    )
                    .heightIn(min = 54.dp)
                    .padding(horizontal = 7.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    modelName,
                    color = if (selected) WorkbenchAmber else WorkbenchWhite,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    minLines = 2,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp,
                )
            }
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(5.dp)
                    .background(WorkbenchAmber),
            )
        }
    }
}

@Composable
private fun FilmBoxFallback(modifier: Modifier = Modifier) {
    Box(modifier.background(WorkbenchPanel)) {
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(4) {
                Box(Modifier.size(4.dp).background(WorkbenchLineBright))
            }
        }
        Text(
            "35 mm",
            color = WorkbenchMuted,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.Center),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .width(18.dp)
                .height(1.dp)
                .background(WorkbenchAmber.copy(alpha = 0.7f)),
        )
    }
}

// ---------------------------------------------------------------------------
// Live camera stage
// ---------------------------------------------------------------------------

@Composable
private fun CameraWorkbench(
    selection: WorkbenchSelection,
    onSelectionChange: (WorkbenchSelection) -> Unit,
    onOpenPhotoPicker: () -> Unit,
    onUsePhoto: (Uri, WorkbenchSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember(context) { CaptureFileStore(context) }
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var captureGeneration by remember { mutableIntStateOf(0) }
    var captureError by rememberSaveable { mutableStateOf<String?>(null) }
    var previewStatus by remember { mutableStateOf(CameraPreviewStatus()) }
    val pendingFile = pendingPath?.let(::File)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        permissionDenied = !granted
    }
    val flashMode = when (selection.flash) {
        WorkbenchFlash.AUTO -> ImageCapture.FLASH_MODE_AUTO
        WorkbenchFlash.OFF -> ImageCapture.FLASH_MODE_OFF
        WorkbenchFlash.ON -> ImageCapture.FLASH_MODE_ON
    }
    SideEffect { previewStatus.imageCapture?.flashMode = flashMode }

    fun discardPending() {
        pendingFile?.let { runCatching { store.discard(it) } }
        pendingPath = null
    }
    fun capturePhoto() {
        val capture = previewStatus.imageCapture
        if (!previewStatus.ready || capture == null || capturing) return
        val file = runCatching { store.newFile() }.getOrNull()
        if (file == null) {
            captureError = resources.getString(R.string.camera_capture_unavailable)
            return
        }
        val requestGeneration = captureGeneration
        capturing = true
        captureError = null
        capture.flashMode = flashMode
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (requestGeneration == captureGeneration) {
                        pendingPath = file.absolutePath
                        capturing = false
                    } else {
                        runCatching { store.discard(file) }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runCatching { store.discard(file) }
                    capturing = false
                    captureError = resources.getString(
                        R.string.camera_capture_failed,
                        exception.message ?: "ImageCaptureException",
                    )
                }
            },
        )
    }
    BackHandler(enabled = pendingFile != null) { discardPending() }
    DisposableEffect(Unit) {
        onDispose {
            captureGeneration++
            if (activity?.isChangingConfigurations != true) {
                pendingFile?.let { runCatching { store.discard(it) } }
            }
        }
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier) {
        when {
            pendingFile != null -> CaptureConfirmation(
                file = pendingFile,
                store = store,
                errorMessage = captureError,
                onRetake = {
                    discardPending()
                    captureError = null
                },
                onUse = {
                    val file = pendingFile
                    val uri = file?.let { runCatching { store.uriFor(it) }.getOrNull() }
                    if (uri == null) {
                        captureError = resources.getString(R.string.camera_capture_unavailable)
                    } else {
                        pendingPath = null
                        onUsePhoto(uri, selection)
                    }
                },
            )
            !hasCamera -> CameraMessage(
                body = stringResource(R.string.camera_unavailable),
                onOpenPhotoPicker = onOpenPhotoPicker,
            )
            !permissionGranted -> PermissionExplanation(
                denied = permissionDenied,
                onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                },
                onOpenPhotoPicker = onOpenPhotoPicker,
            )
            else -> CameraStage(
                context = context,
                lifecycleOwner = lifecycleOwner,
                lensFacing = if (selection.lens == WorkbenchLens.BACK) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                },
                flash = selection.flash,
                capturing = capturing,
                previewStatus = previewStatus,
                errorText = captureError ?: if (previewStatus.failed) {
                    stringResource(R.string.camera_failed)
                } else {
                    null
                },
                onStatus = { previewStatus = it },
                onFlash = {
                    onSelectionChange(selection.copy(flash = nextFlash(selection.flash)))
                },
                onFlip = {
                    onSelectionChange(
                        selection.copy(
                            lens = if (selection.lens == WorkbenchLens.BACK) {
                                WorkbenchLens.FRONT
                            } else {
                                WorkbenchLens.BACK
                            },
                        ),
                    )
                },
                onCapture = { capturePhoto() },
            )
        }
    }
}

@Composable
private fun CameraStage(
    context: android.content.Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    lensFacing: Int,
    flash: WorkbenchFlash,
    capturing: Boolean,
    previewStatus: CameraPreviewStatus,
    errorText: String?,
    onStatus: (CameraPreviewStatus) -> Unit,
    onFlash: () -> Unit,
    onFlip: () -> Unit,
    onCapture: () -> Unit,
) {
    val flipDescription = stringResource(R.string.camera_action_flip)
    Column(Modifier.fillMaxSize().background(WorkbenchBody)) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            CameraPreview(
                context = context,
                lifecycleOwner = lifecycleOwner,
                lensFacing = lensFacing,
                onStatus = onStatus,
                modifier = Modifier.fillMaxSize(),
            )
            ViewfinderCornerMarks(Modifier.fillMaxSize())
            RenderNotePlate(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 24.dp, end = 24.dp),
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(48.dp)
                    .alpha(if (previewStatus.canFlip && !capturing) 1f else 0.35f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp))
                    .clickable(
                        enabled = previewStatus.canFlip && !capturing,
                        role = Role.Button,
                        onClick = onFlip,
                    )
                    .semantics { contentDescription = flipDescription },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SpectraIcons.FlipCamera,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (errorText != null) {
                Text(
                    errorText,
                    color = Color(0xFFFFB4AB),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 72.dp, bottom = 16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.Black.copy(alpha = 0.68f))
                        .padding(8.dp),
                )
            }
        }
        ShutterDock(
            ready = previewStatus.ready,
            capturing = capturing,
            flash = flash,
            onFlash = onFlash,
            onCapture = onCapture,
        )
    }
}

@Composable
private fun ViewfinderCornerMarks(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val inset = 10.dp.toPx()
        val arm = 18.dp.toPx()
        val stroke = 1.dp.toPx()
        val color = WorkbenchAmber.copy(alpha = 0.42f)
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * arm, y), stroke)
            drawLine(color, Offset(x, y), Offset(x, y + dy * arm), stroke)
        }
        corner(inset, inset, 1f, 1f)
        corner(size.width - inset, inset, -1f, 1f)
        corner(inset, size.height - inset, 1f, -1f)
        corner(size.width - inset, size.height - inset, -1f, -1f)
    }
}

@Composable
private fun RenderNotePlate(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color.Black.copy(alpha = 0.58f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(3.dp).background(WorkbenchAmber))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.workbench_render_note),
            color = WorkbenchWhite,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Shutter dock
//
// A rectangular film-door slab with a hinge edge, a latch grip and a persistent
// READY / CAPTURING state. It is not a Material Button and keeps the same
// capture callback, ready gate and capturing gate as before.
// ---------------------------------------------------------------------------

@Composable
private fun ShutterDock(
    ready: Boolean,
    capturing: Boolean,
    flash: WorkbenchFlash,
    onFlash: () -> Unit,
    onCapture: () -> Unit,
) {
    val enabled = ready && !capturing
    Column(Modifier.fillMaxWidth().background(WorkbenchBody)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(WorkbenchLine))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlashPlate(flash = flash, enabled = !capturing, onClick = onFlash)
            FilmDoorShutter(
                enabled = enabled,
                capturing = capturing,
                onCapture = onCapture,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FlashPlate(
    flash: WorkbenchFlash,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val text = stringResource(
        when (flash) {
            WorkbenchFlash.AUTO -> R.string.workbench_flash_auto
            WorkbenchFlash.OFF -> R.string.workbench_flash_off
            WorkbenchFlash.ON -> R.string.workbench_flash_on
        },
    )
    val shape = RoundedCornerShape(2.dp)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(WorkbenchPanel)
            .border(1.dp, WorkbenchLine, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = text }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(5.dp)
                .background(
                    if (flash == WorkbenchFlash.OFF) WorkbenchLineBright else WorkbenchAmber,
                ),
        )
        Text(
            text,
            color = if (enabled) WorkbenchWhite else WorkbenchMuted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FilmDoorShutter(
    enabled: Boolean,
    capturing: Boolean,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(3.dp)
    val title = stringResource(R.string.workbench_shutter).uppercase(Locale.ROOT)
    val state = when {
        capturing -> stringResource(R.string.camera_capturing)
        enabled -> stringResource(R.string.workbench_shutter_ready)
        else -> stringResource(R.string.workbench_shutter_unavailable)
    }
    val description = stringResource(R.string.workbench_shutter_desc)
    val active = enabled || capturing
    val border = if (active) WorkbenchAmber else WorkbenchLine
    val fill = when {
        capturing -> WorkbenchAmber.copy(alpha = 0.20f)
        enabled -> WorkbenchAmber.copy(alpha = 0.10f)
        else -> WorkbenchPanel
    }
    val titleColor = if (active) WorkbenchAmber else WorkbenchMuted
    val stateColor = when {
        capturing -> WorkbenchAmber
        enabled -> WorkbenchWhite
        else -> WorkbenchMuted
    }
    Box(
        modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onCapture)
            .semantics {
                contentDescription = description
                stateDescription = state
            },
    ) {
        // Hinge edge — the door is part of the body, not a floating pill.
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(4.dp)
                .height(28.dp)
                .background(if (active) WorkbenchAmber else WorkbenchLineBright),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = titleColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state,
                    color = stateColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (capturing) {
                CircularProgressIndicator(
                    color = WorkbenchAmber,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Canvas(Modifier.width(18.dp).height(24.dp)) {
                    val stroke = 1.dp.toPx()
                    val color = if (active) WorkbenchAmber else WorkbenchLineBright
                    for (row in 0 until 3) {
                        val y = size.height * (0.28f + row * 0.22f)
                        drawLine(
                            color = color,
                            start = Offset(size.width * 0.15f, y),
                            end = Offset(size.width * 0.85f, y),
                            strokeWidth = stroke,
                        )
                    }
                }
            }
        }
    }
}

private fun nextFlash(flash: WorkbenchFlash): WorkbenchFlash = when (flash) {
    WorkbenchFlash.AUTO -> WorkbenchFlash.OFF
    WorkbenchFlash.OFF -> WorkbenchFlash.ON
    WorkbenchFlash.ON -> WorkbenchFlash.AUTO
}
