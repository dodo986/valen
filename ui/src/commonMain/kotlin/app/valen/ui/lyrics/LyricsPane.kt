/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.valen.core.LyricLine
import app.valen.core.Lyrics
import app.valen.core.LyricsState
import app.valen.core.LyricsTiming
import app.valen.core.PlaybackCoordinator
import app.valen.core.Track
import app.valen.ui.components.EmptyState
import app.valen.ui.components.bleed
import app.valen.ui.components.tap
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.LyricType
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The song's words on the stage. Timed lyrics follow the song: the line being sung stands at a
 * fixed height, a little forward of the others, its words filling in as they are sung, and the
 * pen goes over some of them once they are (see [markWords]). The lines travel on a soft spring
 * and the ones below pour after the one that leads. A scroll by hand looks around; after a moment
 * the words find the song again. A tap on a line plays from it.
 *
 * Words that are not timed read as a page; while they are looked up, flat blocks stand where the
 * lines will be; a song with none says so in a sentence.
 */
@Composable
fun LyricsPane(
    coordinator: PlaybackCoordinator,
    track: Track,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val book = coordinator.lyrics
    var attempt by remember(track.key) { mutableIntStateOf(0) }
    val state by produceState(book.known(track) ?: LyricsState.Looking, track.key, attempt) {
        // A new song starts from what is known of it, never from the last song's words.
        value = if (attempt > 0) LyricsState.Looking else book.known(track) ?: LyricsState.Looking
        value = book.lyricsFor(track, again = attempt > 0)
    }
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = state,
        contentKey = { s -> if (s is LyricsState.Found) s.lyrics else s::class },
        transitionSpec = {
            // The blocks go before the lines come: the two never stand on each other.
            if (reduced) fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
            else fadeIn(tween(Motion.EnterMs, delayMillis = Motion.ExitMs * 3 / 4, easing = Motion.Enter)) togetherWith fadeOut(Motion.exit())
        },
        label = "lyrics",
        modifier = modifier.semantics { contentDescription = "Lyrics" },
    ) { shown ->
        when (shown) {
            LyricsState.Looking -> LyricsBlocks(compact)
            is LyricsState.Found -> when {
                shown.lyrics.instrumental -> PaneMessage("No lyrics", "This one is an instrumental.")
                shown.lyrics.timing == LyricsTiming.None -> UntimedLyrics(shown.lyrics, compact)
                else -> key(track.key) {
                    TimedLyrics(shown.lyrics, track, coordinator, playing, onSeek, compact)
                }
            }
            LyricsState.Missing -> PaneMessage("No lyrics for this song", "None of the lyrics sources has its words.")
            is LyricsState.Failed -> PaneMessage("Lyrics did not load", shown.reason, "Try again") { attempt++ }
        }
    }
}

/** One row of the pane: a line, a pause in the singing, or the credit at the end. */
private sealed class Row(val id: String, val startMs: Long, val endMs: Long) {
    class Line(val index: Int, val line: LyricLine) : Row("l$index", line.startMs, line.endMs)
    class Gap(index: Int, start: Long, end: Long) : Row("g$index", start, end)
    class Credit(val source: String) : Row("credit", Long.MAX_VALUE / 4, Long.MAX_VALUE / 4)
}

private fun rowsOf(lyrics: Lyrics): List<Row> {
    val rows = mutableListOf<Row>()
    val lines = lyrics.lines
    lines.firstOrNull()?.let { if (it.startMs >= GAP_MS) rows += Row.Gap(-1, 0, it.startMs) }
    lines.forEachIndexed { i, line ->
        rows += Row.Line(i, line)
        val next = lines.getOrNull(i + 1)
        if (next != null && next.startMs - line.endMs >= GAP_MS) rows += Row.Gap(i, line.endMs, next.startMs)
    }
    rows += Row.Credit(lyrics.source)
    return rows
}

/** A pause this long between two lines shows as its own row of breaths. */
private const val GAP_MS = 4_500L

@Composable
private fun TimedLyrics(
    original: Lyrics,
    track: Track,
    coordinator: PlaybackCoordinator,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    compact: Boolean,
) {
    val lyrics = remember(original) { original.withAnimationTiming() }
    val reduced = LocalReducedMotion.current
    val clock = rememberLyricsClock(coordinator, playing, smooth = lyrics.lines.any { it.timedByWord || it.backing.isNotEmpty() })
    val rows = remember(lyrics) { rowsOf(lyrics) }
    val settings by coordinator.settings.collectAsState()
    // Settings → Lyrics: the pen's marks, and whether the words move or simply light up.
    val marks = remember(lyrics, track.key, settings.lyricsMarks) {
        if (settings.lyricsMarks) markWords(lyrics, track.key.hashCode()) else lyrics.lines.map { emptyList() }
    }
    val still = reduced || !settings.lyricsMotion
    // How long each row still needs the clock: to its end, or to the end of the last mark on it.
    val liveUntil = remember(rows, marks) {
        LongArray(rows.size) { i ->
            when (val row = rows[i]) {
                is Row.Line -> max(row.endMs, marks.getOrNull(row.index)?.mapNotNull { it?.endMs }?.maxOrNull() ?: 0L) + 120
                else -> row.endMs
            }
        }
    }
    val starts = remember(rows) { LongArray(rows.size) { rows[it].startMs } }
    // The row the words stand on: the last one begun.
    val active by remember(rows) {
        derivedStateOf {
            val t = clock.longValue
            var lo = 0
            var hi = rows.lastIndex - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (starts[mid] <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            found
        }
    }
    val sung by remember(rows) { derivedStateOf { active.takeIf { it >= 0 && clock.longValue < rows[it].endMs } ?: -1 } }
    val trailing by remember(rows) {
        derivedStateOf {
            val t = clock.longValue
            val a = active
            var first = a + 1
            for (i in a downTo max(0, a - 3)) if (i != sung && liveUntil[i] > t && starts[i] <= t) first = i
            if (first > a) IntRange.EMPTY else first..a
        }
    }

    val style = if (compact) LyricType.lineCompact else LyricType.line
    val backingStyle = if (compact) LyricType.backingCompact else LyricType.backing
    val voices = lyrics.voices
    val gap = if (compact) Space.step4 else Space.step5
    val density = LocalDensity.current
    val gapPx = with(density) { gap.roundToPx() }
    val anchorShare = if (compact) 0.24f else 0.3f
    val maxLagPx = with(density) { 140.dp.toPx() }

    // The row standing at the anchor, as a fractional row index, sprung toward the active row.
    val pos = remember { Animatable(max(active, 0).toFloat()) }
    var following by remember { mutableStateOf(true) }
    var manual by remember { mutableFloatStateOf(0f) }
    var touches by remember { mutableIntStateOf(0) }
    val tops = remember(rows) { IntArray(rows.size + 1) }
    val scope = rememberCoroutineScope()

    fun yAt(p: Float): Float {
        val n = rows.size
        if (n == 0) return 0f
        val i = floor(p).toInt().coerceIn(0, n - 1)
        val next = tops[(i + 1).coerceAtMost(n)]
        return tops[i] + (p - i) * (next - tops[i])
    }
    fun indexAt(y: Float): Float {
        for (i in 0 until rows.size) {
            val next = tops[i + 1]
            if (y < next || i == rows.size - 1) return i + ((y - tops[i]) / (next - tops[i]).coerceAtLeast(1)).coerceAtLeast(0f)
        }
        return 0f
    }
    suspend fun fold() {
        if (manual != 0f) {
            pos.snapTo(indexAt(yAt(pos.value) + manual))
            manual = 0f
        }
    }

    LaunchedEffect(active, following) {
        if (!following) return@LaunchedEffect
        val target = max(active, 0).toFloat()
        if (reduced) pos.animateTo(target, tween(Motion.ReducedTweenMs)) else pos.animateTo(target, Motion.lyricsFollow())
    }
    LaunchedEffect(touches) {
        if (touches == 0) return@LaunchedEffect
        delay(Motion.LyricsResumeMs)
        fold()
        following = true
    }
    val scrolling = rememberScrollableState { delta ->
        following = false
        val here = yAt(pos.value)
        val lastTop = tops[(rows.size - 1).coerceAtLeast(0)].toFloat()
        manual = (manual - delta).coerceIn(tops[0] - here, lastTop - here)
        touches++
        delta
    }

    val dim = 0.4f
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .fadeEdges(top = if (compact) 0.06f else 0.1f, bottom = 0.2f)
            .scrollable(scrolling, Orientation.Vertical),
        content = {
            rows.forEachIndexed { i, row ->
                key(row.id) {
                    val phase = when {
                        i == sung -> LinePhase.Sung
                        i in trailing -> LinePhase.Trailing
                        i <= active -> LinePhase.Past
                        else -> LinePhase.Upcoming
                    }
                    Arrive(index = i, from = max(active, 0)) {
                        when (row) {
                            is Row.Line -> {
                                val end = voices.size >= 2 && row.line.voice == voices[1]
                                LineRow(
                                    line = row.line,
                                    marks = marks.getOrNull(row.index).orEmpty(),
                                    phase = phase,
                                    clock = clock,
                                    style = style,
                                    backingStyle = backingStyle,
                                    alignEnd = end,
                                    dim = dim,
                                    still = still,
                                    onTap = {
                                        onSeek(row.line.startMs)
                                        scope.launch {
                                            fold()
                                            following = true
                                        }
                                    },
                                )
                            }
                            is Row.Gap -> Breaths(row, phase, clock, style)
                            is Row.Credit -> Text(
                                text = "Lyrics from ${row.source}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ValenColors.Text3,
                                modifier = Modifier.padding(top = Space.step4, bottom = Space.step2),
                            )
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        var y = 0
        placeables.forEachIndexed { i, p ->
            tops[i] = y
            y += p.height + if (p.height > 0) gapPx else 0
        }
        tops[placeables.size] = y
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else y
        layout(width, height) {
            val anchor = height * anchorShare
            val p = pos.value
            val base = yAt(p) + manual
            // While the words travel, each row below the one that leads lags a little more of
            // what is left to go; the ones above go a little ahead.
            val left = if (following) (yAt(pos.targetValue) - yAt(p)).coerceIn(-maxLagPx, maxLagPx) else 0f
            val lead = max(active, 0)
            placeables.forEachIndexed { i, placeable ->
                val d = i - lead
                val k = when {
                    still -> 0f
                    d > 0 -> min(d, Motion.LyricsCascadeLines) * Motion.LyricsCascade
                    d < 0 -> -min(-d, 2) * Motion.LyricsCascade * 0.4f
                    else -> 0f
                }
                // Every row is placed, even far off: each has its own layer, so one out of sight
                // costs nothing to move, and a row that comes and goes from the layout is avoided.
                placeable.placeWithLayer(0, (tops[i] - base + anchor + left * k).roundToInt())
            }
        }
    }
}

/** A line, its backing vocals under it, and the press that plays from it. */
@Composable
private fun LineRow(
    line: LyricLine,
    marks: List<Mark?>,
    phase: LinePhase,
    clock: LongState,
    style: TextStyle,
    backingStyle: TextStyle,
    alignEnd: Boolean,
    dim: Float,
    still: Boolean,
    onTap: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    // Still words keep one size: the sung line is marked by its brightness alone.
    val focus = remember { Animatable(if (phase == LinePhase.Sung || still) 1f else 0f) }
    LaunchedEffect(phase == LinePhase.Sung, still) {
        val target = if (phase == LinePhase.Sung || still) 1f else 0f
        if (reduced || still) focus.snapTo(target) else focus.animateTo(target, Motion.lean())
    }
    val align = if (alignEnd) TextAlign.End else TextAlign.Start
    Box(
        // One button to a screen reader, named by its words: "play from here".
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "Play from here") { onTap(); true }
            },
    ) {
        // The press lies under the words, so marks drawn past them are never clipped by it.
        Box(
            Modifier
                .matchParentSize()
                .bleed(Space.step2)
                .tap(onClick = onTap, shape = Radii.rowShape)
                .clearAndSetSemantics {},
        )
        Column(
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Space.step1)
                .graphicsLayer {
                    val s = Motion.LyricRestScale + (1f - Motion.LyricRestScale) * focus.value
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
                },
        ) {
            if (line.text.isNotBlank()) {
                SungText(
                    text = line.text,
                    words = line.words,
                    marks = marks,
                    phase = phase,
                    clock = clock,
                    style = if (line.aside) backingStyle else style,
                    align = align,
                    dim = if (line.aside) dim * 0.75f else dim,
                    still = still,
                )
            }
            val backing = line.backingText
            if (!backing.isNullOrBlank()) {
                SungText(
                    text = backing,
                    words = line.backing,
                    marks = emptyList(),
                    phase = phase,
                    clock = clock,
                    style = backingStyle,
                    align = align,
                    dim = dim * 0.75f,
                    still = still,
                    modifier = Modifier.padding(top = Space.step1),
                )
            }
        }
    }
}

/**
 * A pause in the singing: three dabs of the marker that fill one after another across it, then
 * swell and go as the next line comes. It takes room only while it is the pause being sung.
 */
@Composable
private fun Breaths(gap: Row, phase: LinePhase, clock: LongState, style: TextStyle) {
    val reduced = LocalReducedMotion.current
    val shown = phase == LinePhase.Sung
    val open = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(shown) {
        val target = if (shown) 1f else 0f
        when {
            reduced -> open.snapTo(target)
            shown -> open.animateTo(1f, tween(Motion.EnterMs, easing = Motion.Enter))
            else -> open.animateTo(0f, tween(Motion.ExitMs, easing = Motion.Exit))
        }
    }
    val primary = ValenColors.Primary
    val em = with(LocalDensity.current) { style.fontSize.toPx() }
    val full = with(LocalDensity.current) { (em * 1.15f).toDp() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(full * open.value),
    ) {
        if (open.value <= 0.01f) return@Canvas
        val t = if (shown) clock.longValue else gap.endMs
        val g = ((t - gap.startMs).toFloat() / (gap.endMs - gap.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        val leaving = ((t - (gap.endMs - BREATH_OUT_MS)).toFloat() / BREATH_OUT_MS).coerceIn(0f, 1f)
        val radius = em * 0.17f
        val y = size.height / 2f
        for (k in 0 until 3) {
            val local = (g * 3.2f - k).coerceIn(0f, 1f)
            val grow = 0.55f + 0.45f * Motion.Kinetic.transform(local)
            // As the next line comes they swell together and are gone.
            val out = 1f + 0.35f * sin(leaving * PI.toFloat())
            val alpha = (0.28f + 0.72f * local) * (1f - leaving) * open.value
            drawCircle(primary.copy(alpha = alpha), radius * grow * out, Offset(radius * 1.2f + k * em * 0.62f, y))
        }
    }
}

private const val BREATH_OUT_MS = 520L

/** A row arriving with the words: it rises into place, the ones nearest the song first. */
@Composable
private fun Arrive(index: Int, from: Int, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val rise = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        delay(min(abs(index - from), Motion.StaggerCap).toLong() * Motion.StaggerMs)
        rise.animateTo(1f, tween(Motion.EnterMs, easing = Motion.Enter))
    }
    val distance = with(LocalDensity.current) { Motion.RiseDistance.toPx() }
    Box(
        Modifier.graphicsLayer {
            alpha = rise.value
            translationY = (1f - rise.value) * distance
        },
    ) { content() }
}

/** While the words are looked up: flat blocks where the lines will stand. No shimmer. */
@Composable
private fun LyricsBlocks(compact: Boolean) {
    val style = if (compact) LyricType.lineCompact else LyricType.line
    val bar = with(LocalDensity.current) { (style.fontSize.toPx() * 0.72f).toDp() }
    Column(Modifier.fillMaxSize().padding(top = Space.step8)) {
        BLOCKS.forEach { share ->
            Box(
                Modifier
                    .fillMaxWidth(share)
                    .height(bar)
                    .background(ValenColors.Skeleton, Radii.pillShape),
            )
            Spacer(Modifier.height(if (compact) Space.step5 else Space.step6))
        }
    }
}

private val BLOCKS = listOf(0.78f, 0.62f, 0.88f, 0.5f, 0.7f, 0.58f)

/** Words with no timing: a page to read, scrolled by hand. */
@Composable
private fun UntimedLyrics(lyrics: Lyrics, compact: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .fadeEdges(top = 0.04f, bottom = 0.16f)
            .verticalScroll(rememberScrollState())
            .padding(top = Space.step4, bottom = Space.step16),
    ) {
        Text(
            text = "These words are not timed to the song.",
            style = MaterialTheme.typography.bodySmall,
            color = ValenColors.Text3,
        )
        Spacer(Modifier.height(Space.step4))
        lyrics.lines.forEach { line ->
            Text(
                text = line.text,
                style = if (compact) LyricType.plain.copy(fontSize = LyricType.backing.fontSize) else LyricType.plain,
                color = ValenColors.Text,
            )
            Spacer(Modifier.height(Space.step2))
        }
        Text(
            text = "Lyrics from ${lyrics.source}",
            style = MaterialTheme.typography.bodySmall,
            color = ValenColors.Text3,
            modifier = Modifier.padding(top = Space.step4),
        )
    }
}

/** A sentence in place of the words, on the pane's left edge. */
@Composable
private fun PaneMessage(title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(top = Space.step8), contentAlignment = Alignment.TopStart) {
        EmptyState(title = title, body = body, actionLabel = actionLabel, onAction = onAction)
    }
}

/** The pane's words thin out toward its top and bottom edges instead of being cut there. */
private fun Modifier.fadeEdges(top: Float, bottom: Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    top to Color.Black,
                    (1f - bottom) to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
