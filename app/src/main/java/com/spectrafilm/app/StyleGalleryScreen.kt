/*
 * Spektrafilm for Android — packaging-led built-in look gallery. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun StyleGalleryScreen(
    preview: Bitmap?,
    thumbnails: Map<String, Bitmap>,
    builtInGroups: Map<String, List<BuiltInPreset>>,
    catalogReady: Boolean,
    appliedBuiltInId: String?,
    appliedBuiltInModified: Boolean,
    amount: Float,
    amountEnabled: Boolean,
    onAmountChange: (Float) -> Unit,
    onApplyBuiltIn: (BuiltInPreset) -> Unit,
    onOpenCustom: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val title = stringResource(R.string.style_gallery_title)
    val packaging = remember(context) {
        runCatching { PackagingCatalog.load(context) }.getOrNull()
    }
    val packagingByStock = remember(packaging) {
        packaging.orEmpty().associateBy(PackagingEntry::stockId)
    }
    BackHandler(onBack = onClose)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { paneTitle = title },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        SpectraIcons.Back,
                        contentDescription = stringResource(R.string.editor_nav_back),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onOpenCustom,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.style_gallery_custom))
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (preview != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.style_gallery_photo),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = stringResource(R.string.style_gallery_photo_desc, title),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .background(SpectraIcons.nearBlackCanvas),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }

                val current = builtInGroups.values.flatten().firstOrNull { it.id == appliedBuiltInId }
                if (current != null) {
                    CurrentLook(
                        preset = current,
                        packaging = packagingByStock[current.filmProfile],
                        modified = appliedBuiltInModified,
                    )
                }

                if (amountEnabled) {
                    EnhancedSlider(
                        label = stringResource(R.string.editor_preset_amount),
                        value = amount * 100f,
                        range = 0f..100f,
                        step = 1f,
                        decimals = 0,
                        default = 100f,
                        tooltip = stringResource(R.string.editor_preset_amount_tooltip),
                        onValueChange = { onAmountChange((it / 100f).coerceIn(0f, 1f)) },
                    )
                }

                if (packaging == null) {
                    Text(
                        stringResource(R.string.style_gallery_assets_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    stringResource(R.string.style_gallery_all),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                when {
                    !catalogReady -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.width(20.dp))
                        Text(stringResource(R.string.editor_preset_loading))
                    }
                    builtInGroups.isEmpty() -> Text(
                        stringResource(R.string.editor_preset_load_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> builtInGroups.forEach { (group, presets) ->
                        Text(
                            BuiltInPresets.groupTitle(context, group),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        PresetGrid(
                            presets = presets,
                            thumbnails = thumbnails,
                            appliedBuiltInId = appliedBuiltInId,
                            packagingByStock = packagingByStock,
                            onApplyBuiltIn = onApplyBuiltIn,
                        )
                    }
                }
                Text(
                    stringResource(R.string.screen_howto_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun CurrentLook(
    preset: BuiltInPreset,
    packaging: PackagingEntry?,
    modified: Boolean,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.style_gallery_current),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PackagingVisual(
                entry = packaging,
                modelName = StockCatalog.displayName(context, preset.filmProfile),
                modifier = Modifier
                    .width(132.dp)
                    .height(112.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    BuiltInPresets.displayName(context, preset),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        if (modified) R.string.editor_preset_applied_modified
                        else R.string.editor_preset_applied,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    StockCatalog.displayName(context, preset.filmProfile),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (preset.printProfile.isNotBlank() && !preset.scanFilm) {
                    Text(
                        StockCatalog.displayName(context, preset.printProfile),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    BuiltInPresets.displayDescription(context, preset),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (packaging != null) {
                    Text(
                        stringResource(
                            if (packaging.mediaType == PackagingMediaType.BOX) {
                                R.string.style_gallery_box
                            } else {
                                R.string.style_gallery_cartridge
                            },
                        ) + " · " + stringResource(R.string.style_gallery_year, packaging.year),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetGrid(
    presets: List<BuiltInPreset>,
    thumbnails: Map<String, Bitmap>,
    appliedBuiltInId: String?,
    packagingByStock: Map<String, PackagingEntry>,
    onApplyBuiltIn: (BuiltInPreset) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val columns = if (fontScale >= 1.3f || maxWidth < 340.dp) 2 else 4
        val spacing = 8.dp
        val itemWidth = (maxWidth - spacing * (columns - 1)) / columns
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = columns,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            presets.forEach { preset ->
                val selected = preset.id == appliedBuiltInId
                Surface(
                    modifier = Modifier
                        .width(itemWidth)
                        .clickable { onApplyBuiltIn(preset) }
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            this.selected = selected
                        },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        PresetVisual(
                            preset = preset,
                            thumbnail = thumbnails[preset.id],
                            packaging = packagingByStock[preset.filmProfile],
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(84.dp),
                        )
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                BuiltInPresets.displayName(LocalContext.current, preset),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            )
                            if (selected) {
                                Text(
                                    stringResource(R.string.editor_preset_applied),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetVisual(
    preset: BuiltInPreset,
    thumbnail: Bitmap?,
    packaging: PackagingEntry?,
    modifier: Modifier,
) {
    val modelName = StockCatalog.displayName(LocalContext.current, preset.filmProfile)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = stringResource(
                    R.string.style_gallery_effect_desc,
                    BuiltInPresets.displayName(LocalContext.current, preset),
                ),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (packaging != null) {
                PackagingVisual(
                    entry = packaging,
                    modelName = modelName,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .width(42.dp)
                        .height(34.dp),
                )
            }
        } else {
            PackagingVisual(
                entry = packaging,
                modelName = modelName,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PackagingVisual(
    entry: PackagingEntry?,
    modelName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, entry?.asset) {
        value = entry?.let { media ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open(media.asset).use(BitmapFactory::decodeStream)
                }.getOrNull()?.asImageBitmap()
            }
        }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (entry != null && bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = modelName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    modelName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.style_gallery_info_card),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
