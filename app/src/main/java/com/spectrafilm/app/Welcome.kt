/*
 * Spektrafilm for Android — single-page welcome / onboarding. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * First run should get out of the way. One scrollable page, left-aligned like a photo tool:
 * a narrow static spectral film mark, the brand, a short statement of what the app does, the
 * three real steps (import → choose a look → fine-tune and save), the on-device privacy promise
 * and one clear "start editing" action. There is no drifting gradient or decorative motion.
 * Secondary actions stack instead of forcing equal-width buttons that truncate at large text.
 *
 * Shown only on first launch (gated by AppSettings.seenOnboarding); re-openable from Settings.
 * Self-contained: no new gradle dependency. The guide, Settings, issue reporting and the
 * spektrafilm/GPLv3 attribution links all stay reachable.
 */
package com.spectrafilm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The static spectral strip at the top of the page. No animation: it is a mark, not a show. */
private val SpectralMarkBrush: Brush = Brush.horizontalGradient(SpektraBrand.spectrum)

/**
 * Single-page onboarding. [onFinish] marks onboarding seen and opens the editor. The page
 * keeps quick links to the in-app guide, Settings, and issue reporting; the guide and the
 * About screen continue to carry the GPL/Spektrafilm attribution.
 */
@Composable
fun WelcomeFlow(
    onFinish: () -> Unit,
    onOpenSettings: () -> Unit,
    onReportIssue: () -> Unit,
) {
    // Show the How-To guide over the welcome page when the user taps the button.
    var showHowTo by remember { mutableStateOf(false) }

    if (showHowTo) {
        HowToUseScreen(onBack = { showHowTo = false })
        return
    }

    // All visible copy below comes from stringResource, so it follows the in-app language.
    val ctx = LocalContext.current
    val opensInBrowser = stringResource(R.string.screen_opens_in_browser)
    val linkSemantics = Modifier.semantics { onClick(label = opensInBrowser, action = null) }
    // Hoisted: a Modifier.semantics lambda is not a composable scope.
    val paneTitleText = stringResource(R.string.screen_welcome_page1_title)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The welcome page is a Compose overlay over the editor, not a platform Dialog, so
            // it names itself as an accessibility pane. The page's own headings, steps and
            // buttons stay separate nodes beneath it.
            .semantics { paneTitle = paneTitleText },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start,
        ) {
            // Narrow spectral film mark: static, like a strip of stock on the light table.
            Box(
                Modifier
                    .width(120.dp)
                    .height(6.dp)
                    .background(SpectralMarkBrush, RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.screen_welcome_page1_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.screen_welcome_page1_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.screen_welcome_steps),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    WelcomeStep(
                        number = 1,
                        title = stringResource(R.string.screen_welcome_step1_title),
                        body = stringResource(R.string.screen_welcome_step1_body),
                    )
                    WelcomeStepDivider()
                    WelcomeStep(
                        number = 2,
                        title = stringResource(R.string.screen_welcome_step2_title),
                        body = stringResource(R.string.screen_welcome_step2_body),
                    )
                    WelcomeStepDivider()
                    WelcomeStep(
                        number = 3,
                        title = stringResource(R.string.screen_welcome_step3_title),
                        body = stringResource(R.string.screen_welcome_step3_body),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.screen_welcome_privacy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(stringResource(R.string.screen_welcome_get_started))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showHowTo = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.screen_how_to_use_app)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.screen_welcome_open_settings)) }

            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onReportIssue,
                // Label only: the button's own click action is kept (null action merges).
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { onClick(label = opensInBrowser, action = null) },
            ) {
                Text(
                    stringResource(R.string.screen_report_issue),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.screen_howto_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { Links.open(ctx, Links.SPEKTRAFILM) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .then(linkSemantics),
                ) {
                    Text(
                        stringResource(R.string.screen_link_spektrafilm),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                TextButton(
                    onClick = { Links.open(ctx, Links.GPLV3) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .then(linkSemantics),
                ) {
                    Text(
                        stringResource(R.string.screen_link_gplv3),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(number: Int, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WelcomeStepDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}
