/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.SignInPrompt
import app.valen.ui.SystemBack
import app.valen.ui.components.KineticText
import app.valen.ui.components.LinkField
import app.valen.ui.components.LiquidWait
import app.valen.ui.components.LocalPageEntrance
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.QuietButton
import app.valen.ui.components.Tag
import app.valen.ui.components.rememberPageEntrance
import app.valen.ui.components.rise
import app.valen.ui.menu.PouredPanel
import app.valen.ui.menu.PouredSheet
import app.valen.ui.menu.stepFill
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

private val PanelWidth: Dp = 440.dp
private val TagWidth: Dp = 112.dp

/**
 * External-browser sign-in sheet shown on the desktop while Valen waits for session cookies from
 * the user's real browser (or lets the user import `cookies.txt` / paste cookies).
 */
@Composable
fun SignInSheetHost(
    coordinator: PlaybackCoordinator,
    compact: Boolean,
    modifier: Modifier = Modifier,
    start: Dp = 0.dp,
    bottom: Dp = 0.dp,
) {
    val active by coordinator.signInPrompt.collectAsState()
    var shown by remember { mutableStateOf<SignInPrompt?>(null) }
    var openKey by remember { mutableIntStateOf(0) }
    if (active != null) {
        if (shown?.provider != active?.provider) openKey++
        shown = active
    }
    val current = shown ?: return
    val open = active != null
    val dismiss = { current.onCancel() }
    val gone = { if (active == null) shown = null }
    SystemBack(enabled = open) { dismiss() }

    Box(modifier = modifier) {
        if (compact) {
            PouredSheet(key = openKey, open = open, onDismiss = dismiss, onGone = gone, closeLabel = "Cancel sign-in") {
                SignInBody(current, glass = false, opening = openKey)
            }
        } else {
            PouredPanel(
                key = openKey,
                open = open,
                anchor = null,
                alignEnd = false,
                sweepKey = current.provider,
                start = start,
                bottom = bottom,
                onDismiss = dismiss,
                onGone = gone,
                width = PanelWidth,
                closeLabel = "Cancel sign-in",
                scrim = true,
            ) {
                SignInBody(current, glass = true, opening = openKey)
            }
        }
    }
}

@Composable
private fun SignInBody(
    prompt: SignInPrompt,
    glass: Boolean,
    opening: Int,
) {
    val entrance = rememberPageEntrance(opening)
    val fill = stepFill(glass)
    val scroll = rememberScrollState()
    var pasted by remember(prompt.provider, opening) { mutableStateOf("") }
    val pickCookieFile = rememberCookieFilePicker { path -> prompt.onImportFile(path) }

    CompositionLocalProvider(LocalPageEntrance provides entrance) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scroll)
                    .padding(
                        start = Space.step5,
                        end = Space.step5,
                        top = if (glass) Space.step6 else Space.step3,
                        bottom = Space.step4,
                    ),
            ) {
                Tag(
                    ink = ValenColors.Primary,
                    mist = ValenColors.Primary,
                    replay = opening,
                    modifier = Modifier.width(TagWidth).rise(0),
                )
                Spacer(Modifier.height(Space.step5))
                KineticText(
                    text = "Sign in to ${prompt.provider.label}",
                    style = MaterialTheme.typography.headlineLarge,
                    color = ValenColors.Text,
                    maxLines = 2,
                    modifier = Modifier.rise(1),
                )
                Spacer(Modifier.height(Space.step2))
                Text(
                    text = "Valen opened ${prompt.provider.label} in your browser. Your password never touches Valen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ValenColors.Text2,
                    modifier = Modifier.rise(2),
                )
                Spacer(Modifier.height(Space.step5))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.step3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Radii.rowShape)
                        .background(fill)
                        .padding(horizontal = Space.step4, vertical = Space.step3)
                        .rise(3),
                ) {
                    LiquidWait(
                        color = ValenColors.Primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = prompt.status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ValenColors.Text,
                        )
                        prompt.error?.let { err ->
                            Spacer(Modifier.height(Space.step1))
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = ValenColors.Danger,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Space.step5))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.step2),
                    modifier = Modifier.fillMaxWidth().rise(4),
                ) {
                    PrimaryButton(
                        label = "Check browser",
                        onClick = prompt.onCheckNow,
                        small = true,
                        modifier = Modifier.weight(1f),
                        stretch = true,
                    )
                    QuietButton(
                        label = "Open browser",
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        onClick = prompt.onOpenBrowser,
                        small = true,
                        modifier = Modifier.weight(1f),
                        stretch = true,
                    )
                }

                Spacer(Modifier.height(Space.step6))
                Text(
                    text = "Or import a session",
                    style = MaterialTheme.typography.headlineSmall,
                    color = ValenColors.Text,
                    modifier = Modifier.rise(5),
                )
                Spacer(Modifier.height(Space.step2))
                Text(
                    text = "If your browser blocks automatic cookie reading, export cookies.txt or paste your ${prompt.cookieHint}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ValenColors.Text2,
                    modifier = Modifier.rise(5),
                )
                Spacer(Modifier.height(Space.step3))
                QuietButton(
                    label = "Import cookies.txt",
                    icon = Icons.Rounded.FolderOpen,
                    onClick = pickCookieFile,
                    small = true,
                    stretch = true,
                    modifier = Modifier.rise(6),
                )
                Spacer(Modifier.height(Space.step3))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.step2),
                    modifier = Modifier.fillMaxWidth().rise(7),
                ) {
                    LinkField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        placeholder = "Paste ${prompt.cookieHint}",
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        label = "Use",
                        enabled = pasted.isNotBlank(),
                        onClick = { prompt.onPasteCookie(pasted) },
                        small = true,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Space.step5, end = Space.step5, bottom = if (glass) Space.step5 else Space.step2)
                    .rise(8),
            ) {
                QuietButton(
                    label = "Cancel",
                    onClick = prompt.onCancel,
                    stretch = true,
                )
            }
        }
    }
}
