/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.welcome

import app.valen.ui.typing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.core.Track
import app.valen.ui.LocalAppScope
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.components.AmbientWash
import app.valen.ui.components.ArtworkSquare
import app.valen.ui.components.IconTap
import app.valen.ui.components.ItemArrival
import app.valen.ui.components.KineticText
import app.valen.ui.components.PickChips
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.QuietButton
import app.valen.ui.components.SearchField
import app.valen.ui.components.SegmentedFilter
import app.valen.ui.components.ServiceTile
import app.valen.ui.components.Tag
import app.valen.ui.components.arrive
import app.valen.ui.components.markerSwipe
import app.valen.ui.components.rememberPageEntrance
import app.valen.ui.components.LocalPageEntrance
import app.valen.ui.profile.CrtTv
import app.valen.ui.profile.PictureKind
import app.valen.ui.profile.rememberPicturePicker
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Step { Hello, Name, Artists, Songs, Picture, Basics, Done }

/** Artists to start from before anything is searched: a spread of sounds, nothing more. */
private val Starters = listOf(
    "Daft Punk", "Taylor Swift", "Kendrick Lamar", "Billie Eilish", "Arctic Monkeys", "The Weeknd",
    "Radiohead", "Frank Ocean", "Dua Lipa", "Tame Impala", "Drake", "Lana Del Rey",
    "Gorillaz", "SZA", "Fred again..", "Bad Bunny", "Tyler, The Creator", "Mitski",
    "Linkin Park", "Joji", "Burial", "Bicep", "Phoebe Bridgers", "Travis Scott",
)

/**
 * The welcome, shown once on the first start: a hello, then a name, artists and songs the person
 * loves (the taste starts from these), a picture for their set, their services and colour, and
 * done. Every step can be skipped but the first; nothing is asked twice.
 */
@Composable
fun WelcomeFlow(coordinator: PlaybackCoordinator, modifier: Modifier = Modifier) {
    val scope = LocalAppScope.current
    val settings by coordinator.settings.collectAsState()
    var step by remember { mutableStateOf(Step.Hello) }
    var name by remember { mutableStateOf(settings.profileName) }
    val artists = remember { mutableStateListOf<String>().apply { addAll(settings.favouriteArtists) } }
    val songs = remember { mutableStateListOf<Track>().apply { addAll(settings.favouriteSongs) } }
    val reduced = LocalReducedMotion.current

    fun finish() {
        coordinator.setProfileName(name.trim())
        coordinator.setFavourites(artists.toList(), songs.toList())
        coordinator.setOnboarded(true)
    }

    fun go(next: Step) { step = next }

    Box(modifier = modifier.fillMaxSize().background(ValenColors.Background)) {
        AmbientWash(tones = ArtworkPalette.Neutral, drift = true, fade = 0.8f, modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = LocalChrome.current.gutter)) {
            Spacer(Modifier.height(Space.step6))
            Dots(current = step.ordinal, count = Step.entries.size, modifier = Modifier.align(Alignment.CenterHorizontally))
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    if (reduced) {
                        fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
                    } else {
                        (slideInHorizontally(Motion.enter()) { if (forward) it / 3 else -it / 3 } + fadeIn(Motion.enter()) +
                            scaleIn(Motion.enter(), initialScale = 0.96f)) togetherWith
                            (slideOutHorizontally(Motion.exit()) { if (forward) -it / 4 else it / 4 } + fadeOut(Motion.exit()))
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "welcomeStep",
            ) { shown ->
                val entrance = rememberPageEntrance(shown)
                CompositionLocalProvider(LocalPageEntrance provides entrance) {
                    Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .widthIn(max = 620.dp)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = Space.step8),
                        ) {
                            when (shown) {
                                Step.Hello -> Hello(onStart = { go(Step.Name) })
                                Step.Name -> NameStep(name = name, onName = { name = it.take(40) })
                                Step.Artists -> ArtistsStep(coordinator, artists)
                                Step.Songs -> SongsStep(coordinator, songs)
                                Step.Picture -> PictureStep(coordinator)
                                Step.Basics -> BasicsStep(coordinator, onConnect = { id -> scope.launch { coordinator.connect(id) } })
                                Step.Done -> DoneStep(name = name, onGo = ::finish)
                            }
                        }
                    }
                }
            }
            if (step != Step.Hello && step != Step.Done) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = Space.step6),
                ) {
                    QuietButton(label = "Back", onClick = { go(Step.entries[step.ordinal - 1]) })
                    Spacer(Modifier.weight(1f))
                    val ready = when (step) {
                        Step.Name -> name.isNotBlank()
                        Step.Artists -> artists.isNotEmpty()
                        Step.Songs -> songs.isNotEmpty()
                        else -> true
                    }
                    if (!ready) {
                        QuietButton(label = "Skip", onClick = { go(Step.entries[step.ordinal + 1]) })
                    } else {
                        PrimaryButton(label = "Next", onClick = { go(Step.entries[step.ordinal + 1]) })
                    }
                }
            }
        }
    }
}

/** Where you are in the welcome: dots, the current one a stretched pill of the one colour. */
@Composable
private fun Dots(current: Int, count: Int, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        repeat(count) { i ->
            val width by animateFloatAsState(if (i == current) 28f else 8f, Motion.liquid(), label = "dot")
            val colour by animateColorAsState(if (i <= current) ValenColors.Primary else ValenColors.Surface2, Motion.standard(), label = "dotColour")
            Box(Modifier.size(width = width.dp, height = 8.dp).clip(Radii.pillShape).background(colour))
        }
    }
}

@Composable
private fun Title(text: String, line: String? = null) {
    KineticText(
        text = text,
        style = if (LocalChrome.current.compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
        color = ValenColors.Text,
        maxLines = 2,
        underline = ValenColors.Primary,
    )
    if (line != null) {
        Spacer(Modifier.height(Space.step2))
        Text(line, style = MaterialTheme.typography.bodyLarge, color = ValenColors.Text2, textAlign = TextAlign.Center, modifier = Modifier.arrive(1))
    }
    Spacer(Modifier.height(Space.step6))
}

@Composable
private fun Hello(onStart: () -> Unit) {
    Spacer(Modifier.height(Space.step10))
    Tag(ink = ValenColors.Primary, mist = ValenColors.Primary, modifier = Modifier.width(300.dp).arrive(0, ItemArrival.Slap))
    Spacer(Modifier.height(Space.step8))
    Title("Hi there 🥺", "Let's set up Valen. It takes a minute.")
    PrimaryButton(label = "Start", onClick = onStart, modifier = Modifier.arrive(2))
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit) {
    Title("What should we call you?")
    val style = MaterialTheme.typography.displaySmall.copy(color = ValenColors.Text, textAlign = TextAlign.Center)
    BasicTextField(
        value = name,
        onValueChange = onName,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(ValenColors.Primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier
            .typing()
            .fillMaxWidth()
            .markerSwipe(active = name.isNotBlank(), color = ValenColors.Container, seed = 7)
            .arrive(2),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.Center) {
                if (name.isEmpty()) Text("Your name", style = style.copy(color = ValenColors.Text3))
                field()
            }
        },
    )
}

@Composable
private fun ArtistsStep(coordinator: PlaybackCoordinator, picked: MutableList<String>) {
    Title("Pick artists you love", if (picked.isEmpty()) "Pick a few, or search for yours." else "${picked.size} picked")
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { found = emptyList(); return@LaunchedEffect }
        delay(350)
        found = runCatching { coordinator.unifiedSearch(query).tracks.map { it.artist.substringBefore(",").trim() } }
            .getOrDefault(emptyList()).filter { it.isNotBlank() }.distinct().take(12)
    }
    SearchField(value = query, onValueChange = { query = it }, placeholder = "Search artists", onClear = { query = "" }, modifier = Modifier.arrive(2))
    Spacer(Modifier.height(Space.step5))
    val shown = (picked + if (query.isBlank()) Starters else found).distinct()
    PickChips(
        options = shown,
        selected = { it in picked },
        onToggle = { if (it in picked) picked.remove(it) else picked.add(it) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SongsStep(coordinator: PlaybackCoordinator, picked: MutableList<Track>) {
    Title("Songs on repeat", if (picked.isEmpty()) "Search and add a few." else "${picked.size} added")
    if (picked.isNotEmpty()) {
        // The picks as a fanned stack of covers: each new one slaps on top.
        Box(modifier = Modifier.height(96.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val last = picked.takeLast(6)
            last.forEachIndexed { i, track ->
                val spread = (i - (last.size - 1) / 2f)
                ArtworkSquare(
                    url = track.artworkUrl,
                    size = 80.dp,
                    shape = Radii.cardShape,
                    modifier = Modifier
                        .offset(x = (spread * 44).dp)
                        .graphicsLayer { rotationZ = spread * 6f }
                        .arrive(0, ItemArrival.Slap),
                )
            }
        }
        Spacer(Modifier.height(Space.step5))
    }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Track>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { found = emptyList(); return@LaunchedEffect }
        delay(350)
        found = runCatching { coordinator.unifiedSearch(query).tracks }.getOrDefault(emptyList()).take(8)
    }
    SearchField(value = query, onValueChange = { query = it }, placeholder = "Search songs", onClear = { query = "" })
    Spacer(Modifier.height(Space.step3))
    found.forEachIndexed { i, track ->
        val added = picked.any { it.key == track.key }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(60.dp).arrive(i),
        ) {
            ArtworkSquare(url = track.artworkUrl, size = 44.dp)
            Column(Modifier.weight(1f).padding(horizontal = Space.step3)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, color = ValenColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconTap(
                icon = if (added) Icons.Rounded.Check else Icons.Rounded.Add,
                label = if (added) "Remove" else "Add",
                tint = if (added) ValenColors.Primary else ValenColors.Text,
                onClick = { if (added) picked.removeAll { it.key == track.key } else picked.add(track) },
            )
        }
    }
}

@Composable
private fun PictureStep(coordinator: PlaybackCoordinator) {
    val settings by coordinator.settings.collectAsState()
    val pickAvatar = rememberPicturePicker(PictureKind.Avatar) { coordinator.setAvatar(it) }
    val pickBanner = rememberPicturePicker(PictureKind.Banner) { coordinator.setBanner(it) }
    Title("Make it yours", "This is your TV. Tap it to pick a picture. GIFs work too.")
    CrtTv(picture = settings.avatar, onTune = pickAvatar, modifier = Modifier.width(380.dp).arrive(2, ItemArrival.Slap))
    Spacer(Modifier.height(Space.step6))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.step2), modifier = Modifier.arrive(3)) {
        PrimaryButton(label = if (settings.avatar == null) "Pick a picture" else "Change picture", onClick = pickAvatar)
        QuietButton(label = if (settings.banner == null) "Pick a banner" else "Change banner", onClick = pickBanner)
    }
}

@Composable
private fun BasicsStep(coordinator: PlaybackCoordinator, onConnect: (ProviderId) -> Unit) {
    val sessions by coordinator.sessions.collectAsState()
    val settings by coordinator.settings.collectAsState()
    Title("Almost done", "Connect what you use. YouTube Music and SoundCloud work without an account.")
    Column(verticalArrangement = Arrangement.spacedBy(Space.step2), modifier = Modifier.fillMaxWidth()) {
        coordinator.services.forEachIndexed { i, provider ->
            ServiceTile(
                provider = provider,
                state = sessions[provider] ?: SessionState.SignedOut,
                onConnect = { onConnect(provider) },
                modifier = Modifier.arrive(2 + i),
            )
        }
    }
    Spacer(Modifier.height(Space.step8))
    Text("Colour", style = MaterialTheme.typography.headlineMedium, color = ValenColors.Text, modifier = Modifier.arrive(5))
    Spacer(Modifier.height(Space.step3))
    SegmentedFilter(
        options = listOf(false, true),
        selected = settings.coverColour,
        onSelect = { coordinator.setCoverColour(it) },
        label = { if (it) "From the cover" else "Black and pink" },
        modifier = Modifier.arrive(6),
    )
}

@Composable
private fun DoneStep(name: String, onGo: () -> Unit) {
    Spacer(Modifier.height(Space.step10))
    Box(
        Modifier
            .size(120.dp)
            .arrive(0, ItemArrival.Slap)
            .drawBehind {
                drawRoundRect(ValenColors.Container, cornerRadius = CornerRadius(size.minDimension / 2f))
            },
        contentAlignment = Alignment.Center,
    ) {
        Tag(ink = ValenColors.OnContainer, mist = ValenColors.OnContainer, initial = true, modifier = Modifier.width(56.dp))
    }
    Spacer(Modifier.height(Space.step8))
    Title(if (name.isBlank()) "You're all set" else "You're all set, ${name.trim()}", "Your home is ready. It gets better the more you listen.")
    PrimaryButton(label = "Let's go", onClick = onGo, modifier = Modifier.arrive(2))
}
