/*
 * Spektrafilm for Android — Diagnostics screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Shows a bounded, redacted crash record and an on-demand redacted logcat snapshot.
 * Nothing leaves the device until the user explicitly copies or shares it.
 */
package com.spectrafilm.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The in-app diagnostics page.
 *
 * The normal Settings entry calls this with no arguments, preserving its existing layout and
 * its on-demand cache/logcat sections. The startup crash path passes the record it already
 * read in [MainActivity.onCreate] together with a fixed title, the startup privacy note and a
 * Continue callback; [includeRuntimeDiagnostics] is false there so the page never asks the
 * engine or logcat to start while the app is still in crash-recovery mode.
 */
@Composable
fun DiagnosticsScreen(
    title: String? = null,
    note: String? = null,
    initialCrash: String? = null,
    includeRuntimeDiagnostics: Boolean = true,
    onContinue: (() -> Unit)? = null,
    applyWindowInsets: Boolean = false,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var crash by remember { mutableStateOf(initialCrash) }
    var log by remember { mutableStateOf<String?>(null) }
    var engineCache by remember { mutableStateOf<String?>(null) }
    // The startup page hands over the record read on the main thread in onCreate, so this
    // composition performs no file or engine work. The normal Settings page still reads the
    // persisted crash and cache snapshot off the main thread.
    LaunchedEffect(Unit) {
        if (initialCrash == null) {
            crash = withContext(Dispatchers.IO) { Diagnostics.lastCrash(ctx) }
        }
        if (includeRuntimeDiagnostics) {
            engineCache = withContext(Dispatchers.Default) { Diagnostics.engineCacheSnapshot() }
        }
    }

    var column = Modifier.fillMaxSize()
    if (applyWindowInsets) column = column.windowInsetsPadding(WindowInsets.systemBars)
    column = column.verticalScroll(rememberScrollState()).padding(16.dp)

    Column(
        column,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        if (includeRuntimeDiagnostics) {
            Text(
                stringResource(R.string.screen_diag_app_version, Diagnostics.appVersion(ctx)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.screen_diag_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- last crash ---
        SectionHeading(stringResource(R.string.screen_diag_last_crash))
        val crashText = crash
        if (crashText == null) {
            Text(
                stringResource(R.string.screen_diag_no_crash), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MonoBlock(crashText)
            // Short visible labels ("Copy"/"Clear") get a fuller spoken description of what they act on.
            val copyDesc = stringResource(R.string.screen_diag_copy_crash_desc)
            val clearDesc = stringResource(R.string.screen_diag_clear_crash_desc)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(crashText)) },
                    modifier = Modifier.semantics { contentDescription = copyDesc },
                ) { Text(stringResource(R.string.screen_diag_copy)) }
                // The startup page is a recovery page: leave clearing to the full Diagnostics
                // screen so continuing cannot accidentally discard the evidence.
                if (onContinue == null) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { Diagnostics.clearLastCrash(ctx) }
                                crash = null
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = clearDesc },
                    ) { Text(stringResource(R.string.screen_diag_clear)) }
                }
            }
        }

        if (includeRuntimeDiagnostics) {
            // --- bounded native cache state ---
            SectionHeading(stringResource(R.string.screen_diag_cache_heading))
            Text(
                stringResource(R.string.screen_diag_cache_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            engineCache?.let { MonoBlock(it) }
                ?: Text(
                    stringResource(R.string.screen_diag_cache_loading),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.Default) {
                        val snapshot = Diagnostics.engineCacheSnapshot()
                        withContext(Dispatchers.Main) { engineCache = snapshot }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.screen_diag_refresh_cache)) }

            // --- logcat snapshot ---
            SectionHeading(stringResource(R.string.screen_diag_logcat_heading))
            Button(onClick = {
                scope.launch { val l = withContext(Dispatchers.IO) { Diagnostics.captureLogcat() }; log = l }
            }, modifier = Modifier.fillMaxWidth()) {
                val labelRes =
                    if (log == null) R.string.screen_diag_capture_logcat
                    else R.string.screen_diag_recapture_logcat
                Text(stringResource(labelRes))
            }
            log?.let { MonoBlock(it) }
        }

        // --- share full report ---
        Button(
            onClick = {
                val crashForReport = crash
                scope.launch {
                    val report = withContext(Dispatchers.IO) {
                        if (includeRuntimeDiagnostics) {
                            Diagnostics.buildReport(ctx)
                        } else {
                            Diagnostics.buildStartupReport(ctx, crashForReport)
                        }
                    }
                    Diagnostics.share(ctx, report)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.screen_diag_share_report)) }

        onContinue?.let { continueAction ->
            Button(
                onClick = continueAction,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.screen_diag_startup_continue)) }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun MonoBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}
