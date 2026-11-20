/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.smart

import app.valen.ui.typing
import androidx.compose.animation.animateColorAsState
import app.valen.ui.components.ScrollPage
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlaylistManager
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.core.SmartSource
import app.valen.core.SmartSpec
import app.valen.core.Track
import app.valen.ui.LocalAppScope
import app.valen.ui.components.ArtworkSquare
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.IconTap
import app.valen.ui.components.ItemArrival
import app.valen.ui.components.KineticText
import app.valen.ui.components.PageBottom
import app.valen.ui.components.PageTop
import app.valen.ui.components.PickChips
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.ProgressRail
import app.valen.ui.components.QuietButton
import app.valen.ui.components.SearchField
import app.valen.ui.components.SegmentedFilter
import app.valen.ui.components.Tag
import app.valen.ui.components.arrive
import app.valen.ui.components.pressScale
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.tap
import app.valen.ui.profile.PictureImage
import app.valen.ui.profile.PictureKind
import app.valen.ui.profile.rememberPicturePicker
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Sounds to build from. Plain names; each is searched as it is. */
private val Genres = listOf(
    "Pop", "Hip hop", "R&B", "Rock", "Indie", "Alternative", "Electronic", "House", "Techno",
    "Drum and bass", "Lo-fi", "Jazz", "Soul", "Funk", "Metal", "Punk", "Classical", "Ambient",
    "K-pop", "J-pop", "Latin", "Afrobeats", "Reggae", "Country", "Phonk", "Hyperpop",
)

private class SourceInfo(val source: SmartSource, val label: String, val hint: String, val icon: ImageVector)

private val Sources = listOf(
    SourceInfo(SmartSource.Favourites, "Favourites", "Artists and songs you picked", Icons.Rounded.Favorite),
    SourceInfo(SmartSource.MostPlayed, "Most played", "What you play most", Icons.AutoMirrored.Rounded.TrendingUp),
    SourceInfo(SmartSource.SpotifyFollows, "Spotify follows", "Artists you follow", Icons.Rounded.Star),
    SourceInfo(SmartSource.Artists, "Pick artists", "Choose them yourself", Icons.Rounded.Person),
    SourceInfo(SmartSource.Genres, "Genres", "Build from a sound", Icons.Rounded.GraphicEq),
)

/**
 * A smart playlist, made here: what to build from, how long, how familiar, a name and a picture.
 * "Make it" searches every service from those seeds and picks by the person's taste; the result
 * can be saved (and made again later from the same recipe) or played straight away.
 */
@Composable
fun MixScreen(
    coordinator: PlaybackCoordinator,
    manager: PlaylistManager,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = LocalAppScope.current
    val sessions by coordinator.sessions.collectAsState()
    val spotify = sessions[ProviderId.SPOTIFY] is SessionState.Connected
    var name by remember { mutableStateOf("") }
    var picture by remember { mutableStateOf<String?>(null) }
    val sources = remember { mutableStateListOf(SmartSource.Favourites, SmartSource.MostPlayed) }
    val artists = remember { mutableStateListOf<String>() }
    val genres = remember { mutableStateListOf<String>() }
    var size by remember { mutableStateOf(50) }
    var discovery by remember { mutableStateOf(0.5f) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<List<Track>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val pickCover = rememberPicturePicker(PictureKind.Cover) { picture = it }

    fun spec() = SmartSpec(sources.toSet(), artists.toList(), genres.toList(), size, discovery)
    fun make() {
        working = true
        failure = null
        progress = 0f
        scope.launch {
            val songs = runCatching { coordinator.mixer().build(spec(), coordinator.taste(), onProgress = { progress = it }) }.getOrDefault(emptyList())
            working = false
            result = songs
            if (songs.isEmpty()) failure = "Nothing found. Try more sources or other artists."
        }
    }
    val title = name.ifBlank { "My mix" }

    ScrollPage(key = "mix", modifier = modifier.fillMaxSize()) {
        PageTop()
        ContentColumn {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KineticText(
                    text = "New mix",
                    style = if (LocalChrome.current.compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                    color = ValenColors.Text,
                    underline = ValenColors.Primary,
                    modifier = Modifier.weight(1f),
                )
                IconTap(icon = Icons.Rounded.Close, label = "Close", onClick = onClose, tint = ValenColors.Text)
            }
            Spacer(Modifier.height(Space.step6))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val side = maxWidth >= 860.dp
                val cover: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MixCover(picture = picture, name = title, working = working, size = if (side) 300.dp else 240.dp)
                        Spacer(Modifier.height(Space.step4))
                        NameField(name = name, onName = { name = it.take(60) })
                        Spacer(Modifier.height(Space.step3))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
                            QuietButton(label = if (picture == null) "Add picture" else "Change picture", icon = Icons.Rounded.Image, small = true, onClick = pickCover)
                            if (picture != null) QuietButton(label = "Remove", small = true, onClick = { picture = null })
                        }
                    }
                }
                val options: @Composable () -> Unit = {
                    Column {
                        Label("Build from")
                        SourceTiles(
                            // "Spotify follows" only where this build has Spotify at all.
                            shown = { it != SmartSource.SpotifyFollows || ProviderId.SPOTIFY in coordinator.services },
                            selected = { it in sources },
                            enabled = { it != SmartSource.SpotifyFollows || spotify },
                            onToggle = { if (it in sources) sources.remove(it) else sources.add(it) },
                        )
                        if (SmartSource.Artists in sources) {
                            Spacer(Modifier.height(Space.step5))
                            ArtistPicker(coordinator, artists)
                        }
                        if (SmartSource.Genres in sources) {
                            Spacer(Modifier.height(Space.step5))
                            Label("Genres")
                            PickChips(options = Genres, selected = { it in genres }, onToggle = { if (it in genres) genres.remove(it) else genres.add(it) })
                        }
                        Spacer(Modifier.height(Space.step6))
                        Label("Length")
                        SegmentedFilter(options = listOf(25, 50, 100), selected = size, onSelect = { size = it }, label = { "$it songs" })
                        Spacer(Modifier.height(Space.step5))
                        Label("Mix")
                        SegmentedFilter(
                            options = listOf(0f, 0.5f, 1f),
                            selected = discovery,
                            onSelect = { discovery = it },
                            label = { when (it) { 0f -> "Familiar"; 1f -> "Fresh"; else -> "Balanced" } },
                        )
                        Spacer(Modifier.height(Space.step6))
                        if (working) {
                            Text("Finding songs…", style = MaterialTheme.typography.titleMedium, color = ValenColors.Text)
                            Spacer(Modifier.height(Space.step2))
                            ProgressRail(fraction = progress, modifier = Modifier.fillMaxWidth())
                        } else {
                            PrimaryButton(
                                label = if (result == null) "Make it" else "Make again",
                                icon = if (result == null) null else Icons.Rounded.Refresh,
                                enabled = sources.isNotEmpty() && (SmartSource.Artists !in sources || artists.isNotEmpty() || sources.size > 1) &&
                                    (SmartSource.Genres !in sources || genres.isNotEmpty() || sources.size > 1),
                                onClick = ::make,
                            )
                        }
                        failure?.let {
                            Spacer(Modifier.height(Space.step3))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text2)
                        }
                    }
                }
                if (side) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.step10)) {
                        Box(Modifier.width(300.dp)) { cover() }
                        Box(Modifier.weight(1f)) { options() }
                    }
                } else {
                    Column {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { cover() }
                        Spacer(Modifier.height(Space.step8))
                        options()
                    }
                }
            }
            val songs = result
            if (!songs.isNullOrEmpty()) {
                Spacer(Modifier.height(Space.step10))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$title · ${songs.size} songs", style = MaterialTheme.typography.headlineMedium, color = ValenColors.Text, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(Space.step3))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
                    PrimaryButton(label = "Save", onClick = {
                        scope.launch {
                            manager.library.add(title, songs, artworkUrl = picture, smart = spec())
                            onSaved()
                        }
                    })
                    QuietButton(label = "Play", icon = Icons.Rounded.PlayArrow, onClick = {
                        scope.launch { coordinator.play(songs.first(), songs, from = title, generated = true) }
                    })
                }
                Spacer(Modifier.height(Space.step4))
                songs.forEachIndexed { i, track -> MixRow(index = i, track = track) }
            }
        }
        PageBottom()
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = ValenColors.Text)
    Spacer(Modifier.height(Space.step3))
}

@Composable
private fun NameField(name: String, onName: (String) -> Unit) {
    val style = MaterialTheme.typography.headlineLarge.copy(color = ValenColors.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    BasicTextField(
        value = name,
        onValueChange = onName,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(ValenColors.Primary),
        modifier = Modifier.typing().widthIn(max = 300.dp).fillMaxWidth(),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.Center) {
                if (name.isEmpty()) Text("Name it", style = style.copy(color = ValenColors.Text3))
                field()
            }
        },
    )
}

/** The five sources as tiles: icon, name, one line; chosen ones fill with the one colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceTiles(
    shown: (SmartSource) -> Boolean,
    selected: (SmartSource) -> Boolean,
    enabled: (SmartSource) -> Boolean,
    onToggle: (SmartSource) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.step2), verticalArrangement = Arrangement.spacedBy(Space.step2)) {
        Sources.filter { shown(it.source) }.forEachIndexed { i, info ->
            val on = selected(info.source)
            val usable = enabled(info.source)
            val fill by animateColorAsState(if (on) ValenColors.Container else ValenColors.Surface, Motion.standard(), label = "sourceFill")
            val lift by animateFloatAsState(if (on) 1f else 0f, Motion.liquid(), label = "sourceLift")
            val source = rememberInteraction()
            Column(
                modifier = Modifier
                    .width(168.dp)
                    .arrive(i, ItemArrival.Slap)
                    .graphicsLayer { translationY = -lift * 4.dp.toPx() }
                    .pressScale(source)
                    .tap(onClick = { if (usable) onToggle(info.source) }, shape = Radii.rowShape, rest = fill, enabled = usable, source = source, role = Role.Checkbox)
                    .padding(Space.step4),
            ) {
                Icon(info.icon, contentDescription = null, tint = if (on) ValenColors.Primary else ValenColors.Text2, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(Space.step3))
                Text(info.label, style = MaterialTheme.typography.titleMedium, color = if (usable) ValenColors.Text else ValenColors.Text3, maxLines = 1)
                Text(
                    if (usable) info.hint else "Connect Spotify first",
                    style = MaterialTheme.typography.bodySmall,
                    color = ValenColors.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ArtistPicker(coordinator: PlaybackCoordinator, picked: MutableList<String>) {
    Label("Artists")
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { found = emptyList(); return@LaunchedEffect }
        delay(350)
        found = runCatching { coordinator.unifiedSearch(query).tracks.map { it.artist.substringBefore(",").trim() } }
            .getOrDefault(emptyList()).filter { it.isNotBlank() }.distinct().take(10)
    }
    SearchField(value = query, onValueChange = { query = it }, placeholder = "Search artists", onClear = { query = "" })
    Spacer(Modifier.height(Space.step3))
    val favourites = coordinator.settings.value.favouriteArtists
    val shown = (picked + if (query.isBlank()) favourites else found).distinct()
    if (shown.isNotEmpty()) {
        PickChips(options = shown, selected = { it in picked }, onToggle = { if (it in picked) picked.remove(it) else picked.add(it) })
    }
}

@Composable
private fun MixRow(index: Int, track: Track) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(60.dp).arrive(index, if (index % 3 == 0) ItemArrival.Slap else ItemArrival.Rise),
    ) {
        Text("${index + 1}", style = timeStyle(), color = ValenColors.Text3, modifier = Modifier.width(32.dp))
        ArtworkSquare(url = track.artworkUrl, size = 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = Space.step3)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, color = ValenColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${track.artist} · ${track.provider.short}", style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The mix's cover: the chosen picture (a GIF moves), or one drawn for it — soft light drifting over
 * the one colour, with the tag and the name. While songs are being found a record slides out from
 * behind it and spins; when they are in, it slides back.
 */
@Composable
fun MixCover(picture: String?, name: String, working: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val loop = rememberInfiniteTransition(label = "mixCover")
    val drift by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "drift")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val out by animateFloatAsState(if (working && !reduced) 1f else 0f, Motion.liquid(), label = "recordOut")
    val primary = ValenColors.Primary
    val container = ValenColors.Container
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        // The record, behind the cover.
        Box(
            Modifier
                .size(size * 0.92f)
                .graphicsLayer {
                    translationX = out * this.size.width * 0.42f
                    rotationZ = if (working && !reduced) spin else 0f
                    alpha = if (out > 0.01f) 1f else 0f
                }
                .drawBehind {
                    val r = this.size.minDimension / 2f
                    drawCircle(Color(0xFF0B0B0D), r)
                    for (k in 1..9) drawCircle(Color.White.copy(alpha = 0.05f), r * (0.4f + k * 0.065f), style = Stroke(1.dp.toPx()))
                    drawCircle(primary, r * 0.3f)
                    drawCircle(Color(0xFF0B0B0D), r * 0.04f)
                    drawLine(Color.White.copy(alpha = 0.18f), center, Offset(center.x + r * 0.9f, center.y), strokeWidth = 2.dp.toPx())
                },
        )
        val breathe = if (working && !reduced) 1f + 0.02f * sin(drift * 2f * PI.toFloat() * 8f) else 1f
        Box(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = breathe; scaleY = breathe }
                .clip(Radii.artworkShape)
                .background(container),
        ) {
            if (picture != null) {
                PictureImage(uri = picture, modifier = Modifier.fillMaxSize())
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val a = drift * 2f * PI.toFloat()
                            for (k in 0..2) {
                                val c = Offset(
                                    this.size.width * (0.5f + 0.32f * cos(a + k * 2.1f)),
                                    this.size.height * (0.5f + 0.32f * sin(a * 1.3f + k * 2.1f)),
                                )
                                drawCircle(
                                    Brush.radialGradient(listOf(primary.copy(alpha = 0.55f), Color.Transparent), center = c, radius = this.size.width * 0.55f),
                                    radius = this.size.width * 0.55f,
                                    center = c,
                                )
                            }
                        },
                )
                Tag(
                    ink = ValenColors.OnContainer,
                    mist = ValenColors.OnContainer,
                    modifier = Modifier.align(Alignment.Center).width(size * 0.62f),
                )
                Text(
                    name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = ValenColors.OnContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(Space.step5),
                )
            }
        }
    }
}
