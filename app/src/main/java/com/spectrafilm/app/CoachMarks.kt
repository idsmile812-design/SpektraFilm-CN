/*
 * Spektrafilm for Android — editor coach marks. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A one-time, dismissible overlay shown the first time the editor is opened (after
 * onboarding). It surfaces the non-obvious affordances — stage + parameter tabs,
 * before/after compare, the magnifier below the preview, pinch-zoom — the way
 * Lightroom's coach marks do, so the gesture-driven editor is discoverable without a
 * manual. Gated by AppSettings.seenEditorCoach; shown exactly once.
 */
package com.spectrafilm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Full-screen one-time coach-mark overlay. [onDismiss] persists the "seen" flag and
 * removes the overlay. Tapping the scrim or the button both dismiss.
 *
 * Accessibility: this is a Compose overlay, not a platform Dialog, so it announces itself
 * as a pane titled with [R.string.tool_coach_title]. The scrim stays its own clickable node
 * (dismiss) and the card's buttons keep their own semantics — nothing here is merged into a
 * single node. The card body scrolls so the "Got it" action is still reachable on a short
 * window or at 200% font scale.
 */
@Composable
fun EditorCoachOverlay(onDismiss: () -> Unit) {
    val paneTitleText = stringResource(R.string.tool_coach_title)
    Box(
        Modifier
            .fillMaxSize()
            // Pane title on the overlay root, not on the scrim's tap target: TalkBack
            // announces "Quick tips" when focus enters the overlay, and the dismiss action
            // keeps its own separate node.
            .semantics { paneTitle = paneTitleText },
        contentAlignment = Alignment.Center,
    ) {
        // Tap anywhere on the scrim to dismiss (no ripple — it's a backdrop).
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.tool_coach_dismiss),
                    onClick = onDismiss,
                ),
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(28.dp),
        ) {
            Column(
                Modifier
                    // The incoming bounded height (screen minus the card's outer padding) is
                    // the scroll viewport: on a short window or at 200% font the tips scroll
                    // and "Got it" stays reachable instead of being clipped off-screen.
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.tool_coach_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                CoachTip(
                    stringResource(R.string.tool_coach_category_title),
                    stringResource(R.string.tool_coach_category_body),
                )
                CoachTip(
                    stringResource(R.string.tool_coach_compare_title),
                    stringResource(R.string.tool_coach_compare_body),
                )
                CoachTip(
                    stringResource(R.string.tool_coach_inspect_title),
                    stringResource(R.string.tool_coach_inspect_body),
                )
                CoachTip(
                    stringResource(R.string.tool_coach_zoom_title),
                    stringResource(R.string.tool_coach_zoom_body),
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(top = 4.dp),
                ) { Text(stringResource(R.string.tool_coach_got_it)) }
            }
        }
    }
}

@Composable
private fun CoachTip(title: String, body: String) {
    // One TalkBack node per tip (title + body); the bullet is decorative.
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Box(
            Modifier
                .padding(top = 6.dp, end = 12.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
