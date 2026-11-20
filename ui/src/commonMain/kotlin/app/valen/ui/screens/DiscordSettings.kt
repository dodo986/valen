/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.PresenceCard
import app.valen.core.PresenceDisplay
import app.valen.core.PresenceNow
import app.valen.core.PresencePause
import app.valen.core.PresenceSettings
import app.valen.core.PresenceStatus
import app.valen.core.PresenceTime
import app.valen.ui.components.Artwork
import app.valen.ui.components.Group
import app.valen.ui.components.ItemArrival
import app.valen.ui.components.LinkField
import app.valen.ui.components.ProgressRail
import app.valen.ui.components.SectionHeader
import app.valen.ui.components.SegmentedFilter
import app.valen.ui.components.arrive
import app.valen.ui.components.rememberPosition
import app.valen.ui.components.tap
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import org.jetbrains.compose.resources.painterResource
import valen.ui.generated.resources.Res
import valen.ui.generated.resources.valen_mark

@Composable
internal fun DiscordPart(coordinator: PlaybackCoordinator) {
    val link = coordinator.presence ?: return
    val settings by coordinator.settings.collectAsState()
    val status by link.status.collectAsState()
    val presence = settings.presence
    val listen by coordinator.nowPlaying.collectAsState()
    val queue by coordinator.queue.collectAsState()
    val index by coordinator.index.collectAsState()
    val track = listen?.track

    val playing = listen?.isPlaying == true || listen?.isBuffering == true
    val durationMs = listen?.durationMs?.takeIf { it > 0 } ?: track?.durationMs ?: 0L
    val now = track?.let {
        PresenceNow(
            track = it,
            playing = playing,
            positionMs = 0L,
            durationMs = durationMs,
            line = if (PresenceCard.needsLine(presence)) PresenceCard.lineAt(coordinator.lyrics.known(it), 0L) else null,
            kept = coordinator.isKept(it),
            place = index + 1,
            of = queue.size,
        )
    }

    Note(presenceSentence(status, presence.enabled, settings.privateMode), Modifier.arrive(1))
    Group(
        listOf {
            SwitchRow(
                title = "Show on Discord",
                body = "Your profile says what you play, with the song and its cover. Nothing to set up on Discord.",
                checked = presence.enabled,
                onChange = { on -> coordinator.setPresence { it.copy(enabled = on) } },
            )
        },
        modifier = Modifier.arrive(2),
    )

    if (!presence.enabled) return

    if (now != null) {
        Spacer(Modifier.height(Space.step8))
        SectionHeader(title = "On your profile", modifier = Modifier.arrive(3, ItemArrival.Swing))
        Note("The card people see while you play. Discord shows it on your own profile too, but not its buttons.", Modifier.arrive(4))
        Group(
            listOf {
                PreviewRow(
                    coordinator = coordinator,
                    card = PresenceCard.of(now, presence, null, 0),
                    presence = presence,
                    playing = playing,
                    durationMs = durationMs,
                )
            },
            modifier = Modifier.arrive(5),
        )
    }

    Spacer(Modifier.height(Space.step8))
    SectionHeader(title = "In the member list", modifier = Modifier.arrive(6, ItemArrival.Swing))
    Note("Under your name Discord says Listening to Valen. It can repeat either line of the card instead.", Modifier.arrive(7))
    SegmentedFilter(
        options = PresenceDisplay.entries,
        selected = presence.display,
        onSelect = { pick -> coordinator.setPresence { it.copy(display = pick) } },
        label = { it.label },
        modifier = Modifier.arrive(8),
    )

    Spacer(Modifier.height(Space.step8))
    SectionHeader(title = "The two lines", modifier = Modifier.arrive(9, ItemArrival.Swing))
    Note(
        "Type anything, and add a word from the song with the chips: Song, Artist, Album, Source " +
            "(where it plays from), Queue, Sung line and Paused fill in as it plays, and the rest stays " +
            "as typed. Sung line or artist says the artist when a song's words are not timed.",
        Modifier.arrive(10),
    )
    Group(
        listOf(
            {
                TemplateRow(
                    title = "First line",
                    template = presence.details,
                    now = now,
                    placeholder = PresenceSettings.DEFAULT_DETAILS,
                    tokens = LINE_TOKENS,
                    onChange = { text -> coordinator.setPresence { it.copy(details = text) } },
                )
            },
            {
                TemplateRow(
                    title = "Second line",
                    template = presence.state,
                    now = now,
                    placeholder = PresenceSettings.DEFAULT_STATE,
                    tokens = LINE_TOKENS,
                    onChange = { text -> coordinator.setPresence { it.copy(state = text) } },
                )
            },
        ),
        modifier = Modifier.arrive(11),
    )

    Spacer(Modifier.height(Space.step8))
    SectionHeader(title = "The pictures", modifier = Modifier.arrive(12, ItemArrival.Swing))
    Note("The cover as the big picture, with Valen's mark in its corner.", Modifier.arrive(13))
    Group(
        listOf(
            {
                SwitchRow(
                    title = "The song's cover",
                    body = "Off, Valen's mark takes the big picture's place.",
                    checked = presence.cover,
                    onChange = { on -> coordinator.setPresence { it.copy(cover = on) } },
                )
            },
            {
                SwitchRow(
                    title = "Valen's mark",
                    body = "The small badge over the cover. With the cover off, it is the big picture instead.",
                    checked = presence.mark,
                    onChange = { on -> coordinator.setPresence { it.copy(mark = on) } },
                )
            },
        ),
        modifier = Modifier.arrive(14),
    )

    Spacer(Modifier.height(Space.step8))
    SectionHeader(title = "On hover", modifier = Modifier.arrive(15, ItemArrival.Swing))
    Note("The words a picture says when someone rests their pointer on it.", Modifier.arrive(16))
    Group(
        listOf(
            {
                TemplateRow(
                    title = "Over the cover",
                    template = presence.largeText,
                    now = now,
                    placeholder = PresenceSettings.DEFAULT_LARGE_TEXT,
                    tokens = HOVER_TOKENS,
                    onChange = { text -> coordinator.setPresence { it.copy(largeText = text) } },
                )
            },
            {
                TemplateRow(
                    title = "Over Valen's mark",
                    template = presence.smallText,
                    now = now,
                    placeholder = PresenceSettings.DEFAULT_SMALL_TEXT,
                    tokens = HOVER_TOKENS,
                    onChange = { text -> coordinator.setPresence { it.copy(smallText = text) } },
                )
            },
        ),
        modifier = Modifier.arrive(17),
    )

    Spacer(Modifier.height(Space.step8))
    SectionHeader(title = "The time", modifier = Modifier.arrive(18, ItemArrival.Swing))
    Note("Under the lines: a bar that fills, a count up, a count down, or nothing.", Modifier.arrive(19))
    SegmentedFilter(
        options = PresenceTime.entries,
        selected = presence.time,
        onSelect = { pick -> coordinator.setPresence { it.copy(time = pick) } },
        label = { it.label },
        modifier = Modifier.arrive(20),
    )

    Spacer(Modifier.height(Space.step8))
    SectionHeader(title = "While paused", modifier = Modifier.arrive(21, ItemArrival.Swing))
    Note("The card when the music stops without Valen being closed. It can stay, waiting, for a while.", Modifier.arrive(22))
    SegmentedFilter(
        options = PresencePause.entries,
        selected = presence.pause,
        onSelect = { pick -> coordinator.setPresence { it.copy(pause = pick) } },
        label = { it.label },
        modifier = Modifier.arrive(23),
    )

    Spacer(Modifier.height(Space.step8))
    SectionHeader(title = "Buttons", modifier = Modifier.arrive(24, ItemArrival.Swing))
    Note("Discord draws up to two of these under the card, only for other people.", Modifier.arrive(25))
    val buttonRows = mutableListOf<@Composable () -> Unit>()
    buttonRows += {
        SwitchRow(
            title = "The song's page",
            body = "Opens the song on the service it plays from.",
            checked = presence.songButton,
            onChange = { on -> coordinator.setPresence { it.copy(songButton = on) } },
        )
    }
    buttonRows += {
        SwitchRow(
            title = "Valen's page",
            body = "The second button. It opens Valen's page.",
            checked = presence.homeButton,
            onChange = { on -> coordinator.setPresence { it.copy(homeButton = on) } },
        )
    }
    Group(buttonRows, modifier = Modifier.arrive(26))
}

@Composable
private fun PreviewRow(
    coordinator: PlaybackCoordinator,
    card: PresenceCard,
    presence: PresenceSettings,
    playing: Boolean,
    durationMs: Long,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.step4, vertical = Space.step4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp)) {
                when (card.largeImage) {
                    null -> Box(Modifier.size(56.dp).clip(Radii.coverShape).background(ValenColors.Fill))
                    PresenceCard.MARK -> Image(
                        painter = painterResource(Res.drawable.valen_mark),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(Radii.coverShape),
                    )
                    else -> Artwork(url = card.largeImage, modifier = Modifier.size(56.dp))
                }
                if (card.smallImage != null) {
                    Image(
                        painter = painterResource(Res.drawable.valen_mark),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(Space.step1)
                            .size(20.dp)
                            .clip(Radii.coverShape)
                            .border(1.dp, ValenColors.Text3, Radii.coverShape),
                    )
                }
            }
            Spacer(Modifier.size(Space.step3))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = ValenColors.Text2,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(Space.step1))
                    Text(
                        text = "Listening to ${card.memberText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ValenColors.Text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (card.details.isNotBlank()) {
                    Text(
                        text = card.details,
                        style = MaterialTheme.typography.titleMedium,
                        color = ValenColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (card.state.isNotBlank()) {
                    Text(
                        text = card.state,
                        style = MaterialTheme.typography.bodySmall,
                        color = ValenColors.Text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (playing && presence.time != PresenceTime.OFF) {
            PreviewTime(
                coordinator = coordinator,
                time = presence.time,
                durationMs = durationMs,
                modifier = Modifier.padding(top = Space.step3),
            )
        }
        if (card.buttons.isNotEmpty()) {
            Spacer(Modifier.height(Space.step3))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
                card.buttons.forEach { button ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 34.dp)
                            .clip(Radii.pillShape)
                            .background(ValenColors.Fill)
                            .padding(horizontal = Space.step3, vertical = Space.step2),
                    ) {
                        Text(
                            text = button.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = ValenColors.Text2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTime(
    coordinator: PlaybackCoordinator,
    time: PresenceTime,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val position by rememberPosition(coordinator)
    when {
        time == PresenceTime.PROGRESS && durationMs > 0 ->
            ProgressRail(fraction = (position.toFloat() / durationMs).coerceIn(0f, 1f), modifier = modifier.fillMaxWidth())
        time == PresenceTime.ELAPSED ->
            Text(PresenceCard.clockTime(position), style = timeStyle(), color = ValenColors.Text2, modifier = modifier)
        time == PresenceTime.REMAINING && durationMs > 0 ->
            Text(
                text = PresenceCard.clockTime((durationMs - position).coerceAtLeast(0)) + " left",
                style = timeStyle(),
                color = ValenColors.Text2,
                modifier = modifier,
            )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateRow(
    title: String,
    template: String,
    now: PresenceNow?,
    placeholder: String,
    tokens: List<TemplateToken>,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.step4, vertical = Space.step3)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = ValenColors.Text)
        Spacer(Modifier.height(Space.step2))
        LinkField(
            value = template,
            onValueChange = { text -> onChange(text.take(TEMPLATE_LIMIT)) },
            placeholder = placeholder,
            icon = Icons.Rounded.TextFields,
        )
        if (now != null) {
            val says = PresenceCard.resolve(template, now)
            Spacer(Modifier.height(Space.step1))
            Text(
                text = if (says.isBlank()) "Shows nothing here." else "Shows: $says",
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Space.step2))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.step2),
            verticalArrangement = Arrangement.spacedBy(Space.step1),
        ) {
            tokens.forEach { token -> TokenChip(token.label) { onChange(insertToken(template, token.text)) } }
        }
    }
}

private data class TemplateToken(val label: String, val text: String)

private val LINE_TOKENS = listOf(
    TemplateToken("Song", "{title}"),
    TemplateToken("Artist", "{artist}"),
    TemplateToken("Song and artist", "{title} · {artist}"),
    TemplateToken("Album", "{album}"),
    TemplateToken("Source", "{source}"),
    TemplateToken("Queue", "{queue}"),
    TemplateToken("Sung line", "{line}"),
    TemplateToken("Sung line or artist", "{line|artist}"),
    TemplateToken("Paused", "{status}"),
)

private val HOVER_TOKENS = listOf(
    TemplateToken("Song", "{title}"),
    TemplateToken("Artist", "{artist}"),
    TemplateToken("Album", "{album}"),
    TemplateToken("Source", "{source}"),
    TemplateToken("Queue", "{queue}"),
)

private const val TEMPLATE_LIMIT = 200

private fun insertToken(template: String, token: String): String {
    val base = template.trimEnd()
    return when {
        base.isBlank() -> token
        base.last() in "·•|/–—-" -> "$base $token"
        else -> "$base · $token"
    }.take(TEMPLATE_LIMIT)
}

@Composable
private fun TokenChip(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(Layout.hitTarget)
            .tap(onClick = onClick, role = Role.Button),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.step1),
            modifier = Modifier
                .heightIn(min = 34.dp)
                .clip(Radii.pillShape)
                .background(ValenColors.Fill)
                .padding(horizontal = Space.step4, vertical = Space.step2),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = ValenColors.Text2,
                modifier = Modifier.size(14.dp),
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = ValenColors.Text, maxLines = 1)
        }
    }
}

private fun presenceSentence(status: PresenceStatus, enabled: Boolean, privateMode: Boolean): String =
    if (privateMode) {
        "Off while Private mode is on: nothing you play is shown on Discord."
    } else when (status) {
        PresenceStatus.Off -> if (enabled) "Starting…" else "Off. Turn it on to show what you play on your Discord profile."
        PresenceStatus.NeedsAppId -> "The application id Valen was given is not a number. Remove it to use Valen's own."
        PresenceStatus.NoDiscord -> "Discord is not open. Valen looks for it every few seconds."
        PresenceStatus.Connecting -> "Looking for Discord…"
        is PresenceStatus.Connected -> status.user?.let { "Showing on Discord as $it." } ?: "Showing on Discord."
        is PresenceStatus.Failed -> status.reason
    }
