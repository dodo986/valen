/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.profile

import app.valen.ui.typing
import androidx.compose.animation.core.Animatable
import app.valen.ui.components.ScrollPage
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.ListeningLog
import app.valen.core.PlaybackCoordinator
import app.valen.ui.components.Artwork
import app.valen.ui.components.ArtworkSquare
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.ItemArrival
import app.valen.ui.components.KineticText
import app.valen.ui.components.PageBottom
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.QuietButton
import app.valen.ui.components.Tag
import app.valen.ui.components.arrive
import app.valen.ui.components.hoverLift
import app.valen.ui.components.markerSwipe
import app.valen.ui.components.pasted
import app.valen.ui.components.rememberInteraction
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import kotlin.math.hypot

/**
 * The profile. A banner across the top of the page; standing in front of it, centred, a 3D
 * colour television showing the profile picture; the name under it; and further down, what the
 * listening done here adds up to — time, the days of the last week, and the most played artist,
 * song, album and playlist.
 *
 * Both pictures are the person's own, chosen with the system's picker and kept in Valen's folder;
 * the name is typed straight onto the page.
 */
@Composable
fun ProfileScreen(coordinator: PlaybackCoordinator, modifier: Modifier = Modifier) {
    val settings by coordinator.settings.collectAsState()
    val log by coordinator.stats.log.collectAsState()
    val chrome = LocalChrome.current
    val pickAvatar = rememberPicturePicker(PictureKind.Avatar) { coordinator.setAvatar(it) }
    val pickBanner = rememberPicturePicker(PictureKind.Banner) { coordinator.setBanner(it) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compact = chrome.compact
        val bannerHeight = if (compact) 200.dp else (maxHeight * 0.4f).coerceIn(240.dp, 440.dp)
        val room = maxWidth - chrome.start - chrome.gutter * 2
        val tvWidth = if (compact) room.coerceAtMost(440.dp) else (room * 0.5f).coerceIn(360.dp, 620.dp)
        // The set stands in front of the banner: this much of it overlaps the banner's foot.
        val overlap = if (compact) 110.dp else bannerHeight * 0.6f

        ScrollPage(key = "profile", modifier = Modifier.fillMaxSize()) {
            Banner(url = settings.banner, modifier = Modifier.fillMaxWidth().height(bannerHeight))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = chrome.start + chrome.gutter, end = chrome.gutter)
                    .pullUp(overlap),
            ) {
                CrtTv(picture = settings.avatar, onTune = pickAvatar, modifier = Modifier.width(tvWidth).arrive(0, ItemArrival.Slap))
                Spacer(Modifier.height(Space.step6))
                Identity(
                    name = settings.profileName,
                    onName = coordinator::setProfileName,
                    onPicture = pickAvatar,
                    onBanner = pickBanner,
                    // Removing forgets Valen's copy as well as the choice.
                    onRemovePicture = settings.avatar?.let { uri -> { forgetPicture(uri); coordinator.setAvatar(null) } },
                    onRemoveBanner = settings.banner?.let { uri -> { forgetPicture(uri); coordinator.setBanner(null) } },
                )
            }
            Spacer(Modifier.height(Space.step16))
            ContentColumn {
                Stats(log = log, privateMode = settings.privateMode)
                // "Your week" beside the listening it is told from, once it is turned on.
                app.valen.ui.recap.WeekCard(coordinator = coordinator, modifier = Modifier.padding(top = Space.step8))
            }
            PageBottom()
        }
    }
}

/** Draws this [by] higher than it is laid out, and gives that space back to what follows. */
private fun Modifier.pullUp(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val up = by.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - up) { placeable.place(0, -up) }
}

/** The name, centred under the set and gone over with a marker, and the two ways to change the pictures. */
@Composable
private fun Identity(
    name: String,
    onName: (String) -> Unit,
    onPicture: () -> Unit,
    onBanner: () -> Unit,
    onRemovePicture: (() -> Unit)?,
    onRemoveBanner: (() -> Unit)?,
) {
    var typed by remember { mutableStateOf(name) }
    val compact = LocalChrome.current.compact
    val style = (if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium)
        .copy(color = ValenColors.Text, textAlign = TextAlign.Center)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = typed,
            onValueChange = {
                typed = it.take(40)
                onName(typed)
            },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(ValenColors.Primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            // As wide as the name, so it sits in the middle and the marker goes over the name only.
            modifier = Modifier
                .typing()
                .width(IntrinsicSize.Max)
                .widthIn(min = 200.dp, max = 560.dp)
                .markerSwipe(active = typed.isNotBlank(), color = ValenColors.Container, seed = 986)
                .arrive(1, ItemArrival.Rise),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.Center) {
                    if (typed.isEmpty()) Text("Your name", style = style.copy(color = ValenColors.Text3), maxLines = 1)
                    field()
                }
            },
        )
        Spacer(Modifier.height(Space.step3))
        Text(
            text = "Tap the set to put your picture on it — a GIF plays on the tube. Pictures are copied into Valen's own folder and stay on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = ValenColors.Text2,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp).arrive(2),
        )
        Spacer(Modifier.height(Space.step5))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.step2), modifier = Modifier.arrive(3)) {
            PrimaryButton(label = "Change picture", icon = Icons.Rounded.Image, onClick = onPicture)
            QuietButton(label = "Change banner", icon = Icons.Rounded.Wallpaper, onClick = onBanner)
        }
        if (onRemovePicture != null || onRemoveBanner != null) {
            Spacer(Modifier.height(Space.step2))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.step2), modifier = Modifier.arrive(4)) {
                if (onRemovePicture != null) {
                    QuietButton(label = "Remove picture", icon = Icons.Rounded.Delete, small = true, onClick = onRemovePicture)
                }
                if (onRemoveBanner != null) {
                    QuietButton(label = "Remove banner", icon = Icons.Rounded.Delete, small = true, onClick = onRemoveBanner)
                }
            }
        }
    }
}

/**
 * What the listening adds up to. Counting is Valen's own and starts with the first song played
 * here, so before that the section says so instead of showing zeros.
 */
@Composable
private fun Stats(log: ListeningLog, privateMode: Boolean) {
    KineticText(
        text = "Your listening",
        style = if (LocalChrome.current.compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
        color = ValenColors.Text,
        underline = ValenColors.Primary,
    )
    Spacer(Modifier.height(Space.step4))
    if (log.totalMs < 1_000) {
        Text(
            text = if (privateMode) {
                "Private mode is on, so nothing is being counted. Turn it off and every song you play " +
                    "adds to this page: the time, your days, and what you come back to most."
            } else {
                "Nothing counted yet. Every song you play in Valen adds to this page: the time, your " +
                    "days, and what you come back to most."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text2,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        return
    }

    // The total, big, in the one colour.
    Text(
        text = listenedFor(log.totalMs),
        style = MaterialTheme.typography.displayLarge,
        color = ValenColors.Primary,
        maxLines = 1,
        modifier = Modifier.arrive(1, ItemArrival.Slap),
    )
    Text(
        text = buildString {
            append(if (log.plays == 1) "1 play" else "${log.plays} plays")
            append(" · ").append(if (log.songs.size == 1) "1 song" else "${log.songs.size} songs")
            if (log.since > 0) append(" · counting since ").append(dateOf(log.since))
        },
        style = MaterialTheme.typography.bodyLarge,
        color = ValenColors.Text2,
        modifier = Modifier.arrive(2),
    )
    if (privateMode) {
        Spacer(Modifier.height(Space.step3))
        Text(
            text = "Private mode is on: nothing new is added to this page while it is out.",
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text2,
            modifier = Modifier.widthIn(max = 560.dp),
        )
    }
    Spacer(Modifier.height(Space.step8))

    Week(log = log, modifier = Modifier.arrive(3))
    Spacer(Modifier.height(Space.step10))

    // The four favourites, as pasted cards.
    val artist = log.topArtists(1).firstOrNull()
    val song = log.topSongs(1).firstOrNull()
    val album = log.topAlbums(1).firstOrNull()
    val playlist = log.topPlaylists(1).firstOrNull()
    val cards = listOfNotNull(
        artist?.let { Favourite("Most played artist", it.first, listenedFor(it.second), log.artworkFor(it.first)) },
        song?.let { Favourite("Most played song", it.title, "${it.artist} · ${listenedFor(it.ms)}", it.artworkUrl) },
        album?.let { Favourite("Most played album", it.first, listenedFor(it.second), log.artworkForAlbum(it.first)) },
        playlist?.let { Favourite("Most played playlist", it.first, listenedFor(it.second), null) },
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 900.dp) 4 else 2
        val gap = Space.step4
        val width = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            cards.chunked(columns).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { c, card ->
                        FavouriteCard(card, width, Modifier.arrive(4 + r * columns + c, ItemArrival.Slap))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(Space.step10))

    // The top five of each, ranked.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = maxWidth >= 900.dp
        val songs: @Composable () -> Unit = {
            Ranked(title = "Top songs", rows = log.topSongs(5).map { Rank(it.title, it.artist, it.ms, it.artworkUrl) })
        }
        val artists: @Composable () -> Unit = {
            Ranked(title = "Top artists", rows = log.topArtists(5).map { Rank(it.first, null, it.second, log.artworkFor(it.first)) })
        }
        if (side) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.step10)) {
                Box(Modifier.weight(1f)) { Column { songs() } }
                Box(Modifier.weight(1f)) { Column { artists() } }
            }
        } else {
            Column {
                songs()
                Spacer(Modifier.height(Space.step8))
                artists()
            }
        }
    }
}

private class Favourite(val label: String, val name: String, val detail: String, val artwork: String?)

/** One favourite as a cover pasted to the wall, with what it is under it. */
@Composable
private fun FavouriteCard(card: Favourite, width: Dp, modifier: Modifier = Modifier) {
    val source = rememberInteraction()
    Column(
        modifier = modifier
            .width(width)
            .pasted(seed = card.name.hashCode(), source = source)
            .hoverLift(source)
            .clip(Radii.cardShape)
            .background(ValenColors.Surface)
            .padding(Space.step3),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(width * 0.62f)
                .clip(Radii.rowShape)
                .background(ValenColors.Container),
        ) {
            if (card.artwork != null) {
                Artwork(url = card.artwork, shape = RectangleShape, modifier = Modifier.fillMaxSize())
            } else {
                Tag(ink = ValenColors.OnContainer, mist = ValenColors.OnContainer, modifier = Modifier.fillMaxWidth(0.6f))
            }
        }
        Spacer(Modifier.height(Space.step3))
        Text(card.label, style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2, maxLines = 1)
        Text(card.name, style = MaterialTheme.typography.titleLarge, color = ValenColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(card.detail, style = MaterialTheme.typography.bodySmall, color = ValenColors.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private class Rank(val name: String, val detail: String?, val ms: Long, val artwork: String?)

/** A ranked five: the rank in the app's voice, the cover, the name, and a bar of the time. */
@Composable
private fun Ranked(title: String, rows: List<Rank>) {
    Text(title, style = MaterialTheme.typography.headlineMedium, color = ValenColors.Text)
    Spacer(Modifier.height(Space.step3))
    val most = rows.maxOfOrNull { it.ms }?.coerceAtLeast(1) ?: 1
    rows.forEachIndexed { i, row ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .arrive(6 + i),
        ) {
            Text(
                text = "${i + 1}",
                style = MaterialTheme.typography.headlineLarge,
                color = if (i == 0) ValenColors.Primary else ValenColors.Text3,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(40.dp),
            )
            ArtworkSquare(url = row.artwork, size = 44.dp)
            Column(modifier = Modifier.weight(1f).padding(horizontal = Space.step3)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, color = ValenColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.detail != null) {
                    Text(row.detail, style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(Space.step1))
                Bar(share = row.ms.toFloat() / most, index = i)
            }
            Text(listenedFor(row.ms), style = timeStyle(), color = ValenColors.Text2)
        }
    }
}

/** A thin bar of the one colour, growing to [share] of the row as it arrives. */
@Composable
private fun Bar(share: Float, index: Int) {
    val reduced = LocalReducedMotion.current
    val grow = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) grow.animateTo(1f, tween(Motion.EnterMs * 2, delayMillis = 200 + index * Motion.StaggerMs * 2, easing = Motion.Enter))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .drawBehind {
                val r = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(ValenColors.Surface2, cornerRadius = r)
                drawRoundRect(ValenColors.Primary, size = Size(size.width * share * grow.value, size.height), cornerRadius = r)
            },
    )
}

private val DayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** The last seven days as bars that grow up from their line, today in the one colour. */
@Composable
private fun Week(log: ListeningLog, modifier: Modifier = Modifier) {
    val today = System.currentTimeMillis() / 86_400_000L
    val days = log.lastDays(today, 7)
    val most = days.maxOrNull()?.coerceAtLeast(1) ?: 1
    val reduced = LocalReducedMotion.current
    val grow = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) grow.animateTo(1f, tween(Motion.EnterMs * 3, delayMillis = 250, easing = Motion.Kinetic)) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text("This week", style = MaterialTheme.typography.headlineMedium, color = ValenColors.Text)
        Spacer(Modifier.height(Space.step4))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.step3), modifier = Modifier.fillMaxWidth().height(160.dp)) {
            days.forEachIndexed { i, ms ->
                val isToday = i == days.lastIndex
                // 1970-01-01 was a Thursday, the fourth day of a week that starts on Monday.
                val name = DayNames[((today - (days.lastIndex - i) + 3) % 7).toInt()]
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds()
                            .drawBehind {
                                val h = size.height * (ms.toFloat() / most).coerceIn(0.02f, 1f) * grow.value
                                drawRoundRect(
                                    if (isToday) ValenColors.Primary else ValenColors.Container,
                                    topLeft = Offset(size.width * 0.2f, size.height - h),
                                    size = Size(size.width * 0.6f, h + 12f),
                                    cornerRadius = CornerRadius(10f, 10f),
                                )
                            },
                    )
                    Spacer(Modifier.height(Space.step2))
                    Text(name, style = timeStyle(), color = if (isToday) ValenColors.Text else ValenColors.Text3)
                    Text(if (ms > 0) shortTime(ms) else "–", style = timeStyle(), color = ValenColors.Text3)
                }
            }
        }
    }
}

/** "42 h 18 min", "18 min", "40 s". */
private fun listenedFor(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        minutes >= 1 -> "$minutes min"
        else -> "${ms / 1000} s"
    }
}

private fun shortTime(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}" else "${minutes}m"
}

private val Months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** "14 November 2026", from epoch milliseconds (UTC), by the civil-from-days method. */
private fun dateOf(ms: Long): String {
    val z = ms / 86_400_000L + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "$d ${Months[(m - 1).toInt()]} $y"
}

/**
 * The wall behind the set: the chosen banner, or a screentone in the one colour with the tag
 * across it. Either fades into the page at its foot.
 */
@Composable
private fun Banner(url: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clipToBounds().background(ValenColors.Background)) {
        if (url != null) {
            PictureImage(uri = url, modifier = Modifier.fillMaxSize())
        } else {
            Screentone(modifier = Modifier.fillMaxSize())
            Tag(
                ink = ValenColors.Primary.copy(alpha = 0.22f),
                mist = ValenColors.Primary.copy(alpha = 0.3f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(0.4f)
                    .padding(end = Space.step10)
                    .graphicsLayer { rotationZ = -6f },
            )
        }
        Spacer(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to ValenColors.Background.copy(alpha = 0.1f),
                        0.5f to ValenColors.Background.copy(alpha = 0.35f),
                        1f to ValenColors.Background,
                    ),
                ),
        )
    }
}

/** A screentone: dots on a grid, largest in the top right, fading out toward the left. */
@Composable
private fun Screentone(modifier: Modifier = Modifier, pitch: Dp = 14.dp) {
    val dot = ValenColors.Container
    Spacer(
        modifier.drawBehind {
            val step = pitch.toPx()
            val reach = hypot(size.width, size.height)
            var row = 0
            var y = 0f
            while (y < size.height + step) {
                var x = if (row % 2 == 0) 0f else step / 2f
                while (x < size.width + step) {
                    val near = 1f - (hypot(size.width - x, y) / reach)
                    val r = step * 0.5f * (near * near * 1.15f).coerceIn(0f, 0.95f)
                    if (r > 0.4f) drawCircle(dot, radius = r, center = Offset(x, y))
                    x += step
                }
                y += step * 0.87f
                row++
            }
        },
    )
}
