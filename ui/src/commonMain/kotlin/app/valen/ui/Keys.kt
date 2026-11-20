/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import app.valen.core.PlaybackCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * What a key does in Valen. Everything here works from anywhere in the app except a text field
 * with the caret in it, where a key types; only the media keys and Esc (which lets go of the field)
 * still work there.
 */
enum class Shortcut {
    PlayPause,
    SeekForward,
    SeekBack,
    Next,
    Previous,
    VolumeUp,
    VolumeDown,
    Mute,
    Like,
    Shuffle,
    Repeat,
    Search,
    Stage,
    Queue,
    Back,
    Help,
    ;

    /** Held down, the key keeps doing it (seeking, the volume); everything else happens once a press. */
    val repeats: Boolean get() = this == SeekForward || this == SeekBack || this == VolumeUp || this == VolumeDown
}

/** One key pressed, as [shortcutOf] reads it: the key, the character it types, the modifiers held. */
internal data class Press(
    val key: Key,
    val char: Char? = null,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
)

/**
 * The shortcut [press] is, if it is one. Letters are matched by the character they type, so they
 * are the same letters on any keyboard layout; arrows, Space and Esc by the key. Alt and Super are
 * left to the desktop, which keeps its own bindings on them. While [typing], a key types instead.
 */
internal fun shortcutOf(press: Press, typing: Boolean = false): Shortcut? {
    if (press.alt || press.meta) return null
    when (press.key) {
        Key.MediaPlayPause -> return Shortcut.PlayPause
        Key.MediaNext -> return Shortcut.Next
        Key.MediaPrevious -> return Shortcut.Previous
        Key.Escape -> return Shortcut.Back
    }
    if (typing) return null
    if (press.key == Key.F1) return Shortcut.Help
    if (press.ctrl) {
        return when (press.key) {
            Key.DirectionRight -> Shortcut.Next
            Key.DirectionLeft -> Shortcut.Previous
            Key.DirectionUp -> Shortcut.VolumeUp
            Key.DirectionDown -> Shortcut.VolumeDown
            Key.F, Key.K -> Shortcut.Search
            else -> null
        }
    }
    when (press.key) {
        Key.Spacebar -> return Shortcut.PlayPause
        Key.DirectionRight -> return Shortcut.SeekForward
        Key.DirectionLeft -> return Shortcut.SeekBack
    }
    val typed = press.char?.takeIf { !it.isISOControl() && !it.isWhitespace() } ?: letterOf(press.key, press.shift)
    return when (typed?.lowercaseChar()) {
        '?' -> Shortcut.Help
        '/' -> Shortcut.Search
        'm' -> Shortcut.Mute
        'l' -> Shortcut.Like
        's' -> Shortcut.Shuffle
        'r' -> Shortcut.Repeat
        'n' -> Shortcut.Stage
        'q' -> Shortcut.Queue
        else -> null
    }
}

/** The character a key types on a US layout, for an event that carries none of its own. */
private fun letterOf(key: Key, shift: Boolean): Char? = when (key) {
    Key.Slash -> if (shift) '?' else '/'
    Key.M -> 'm'
    Key.L -> 'l'
    Key.S -> 's'
    Key.R -> 'r'
    Key.N -> 'n'
    Key.Q -> 'q'
    else -> null
}

/**
 * The window's keyboard, handed to [ValenApp], which says what each [Shortcut] does there. The
 * desktop window gives it every key nothing inside the app took ([handle]); a key the app has no
 * use for goes back to the window.
 */
@Stable
class KeyShortcuts(private val clock: () -> Long = System::currentTimeMillis) {
    /** What the app does for a shortcut; true when it did something. Set by [ValenApp]. */
    internal var perform: ((Shortcut) -> Boolean)? = null

    /** When each key held down last said so: a press within [RepeatGapMs] of it is the key's own repeat. */
    private val held = HashMap<Key, Long>()

    fun handle(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyUp) {
            held.remove(event.key)
            return false
        }
        if (event.type != KeyEventType.KeyDown) return false
        // AWT says "no character" with U+FFFF; that is not one a key typed.
        val char = event.utf16CodePoint.takeIf { it in 1 until 0xFFFF }?.toChar()
        val press = Press(event.key, char, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed)
        return press(press)
    }

    /** Does [shortcut] as if its key were pressed (the screenshot run's `VALEN_CAPTURE_KEYS`). */
    fun run(shortcut: Shortcut): Boolean = perform?.invoke(shortcut) ?: false

    /** One key down, already read; [handle] without a platform event, for tests. */
    internal fun press(press: Press): Boolean {
        val shortcut = shortcutOf(press, typing = Typing.active) ?: return false
        val now = clock()
        val last = held.put(press.key, now)
        // Holding Space must not flick play and pause; held arrows keep seeking.
        if (last != null && now - last < RepeatGapMs && !shortcut.repeats) return true
        return perform?.invoke(shortcut) ?: false
    }

    private companion object {
        /**
         * Longer than any keyboard's delay before it starts repeating a held key, and far longer
         * than the gap between two repeats. A key whose release was never seen (the window lost
         * focus while it was down) is pressed afresh after this long.
         */
        const val RepeatGapMs = 700L
    }
}

/**
 * The text field with the caret in it, if one has: while one has, a key types rather than plays.
 * Only one field has the caret at a time, so one is all there is to know.
 */
internal object Typing {
    internal var owner: TypingNode? = null
    val active: Boolean get() = owner != null
}

/**
 * Marks a text field (or the capsule around one, its clear button included) so the keyboard's
 * shortcuts stand aside while it has the caret, and a click elsewhere lets go of it.
 */
fun Modifier.typing(): Modifier = this then TypingElement

private data object TypingElement : ModifierNodeElement<TypingNode>() {
    override fun create() = TypingNode()
    override fun update(node: TypingNode) = Unit
}

internal class TypingNode : Modifier.Node(), FocusEventModifierNode, GlobalPositionAwareModifierNode {
    /** Where the field is in the window, for [blurOnOutsideClick]. */
    var bounds: Rect = Rect.Zero
        private set

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus) Typing.owner = this else if (Typing.owner === this) Typing.owner = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        bounds = coordinates.boundsInRoot()
    }

    override fun onDetach() {
        if (Typing.owner === this) Typing.owner = null
    }
}

/**
 * A mouse press outside the field with the caret lets go of it, as a page in a browser does: a
 * search typed, then a song clicked, leaves Space to play and pause again. Touch is left alone, so
 * a phone's keyboard does not drop at the start of every scroll. Put on the window's root, which
 * starts at the window's corner, so the press and the field's bounds are measured from one place.
 */
fun Modifier.blurOnOutsideClick(focus: FocusManager): Modifier = pointerInput(focus) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press) continue
            val field = Typing.owner ?: continue
            val change = event.changes.firstOrNull() ?: continue
            if (change.type == PointerType.Mouse && !field.bounds.contains(change.position)) focus.clearFocus()
        }
    }
}

/**
 * The keys that step something (the place in the song, the volume). A held key repeats faster than
 * a service can seek, so each step goes from the last one asked for, not from where the service
 * says it is yet, and only the newest is sent once the one on its way has landed.
 */
internal class KeySteps(
    private val coordinator: PlaybackCoordinator,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var seekTarget = 0L
    private var seekSong: String? = null
    private var seekAt = 0L
    private var seeking: Job? = null

    private var volumeTarget = 0f
    private var volumeAt = 0L
    private var setting: Job? = null

    /** The volume Mute took away, for the next press to give back. */
    private var unmuteTo: Float? = null

    /** Moves the song [deltaMs] on (back, when negative); false when there is no song to move in. */
    fun seekBy(deltaMs: Long): Boolean {
        val snapshot = coordinator.nowPlaying.value ?: return false
        val track = snapshot.track ?: return false
        if (!snapshot.canSeek) return false
        val duration = snapshot.durationMs.takeIf { it > 0 } ?: track.durationMs.takeIf { it > 0 } ?: return false
        val now = clock()
        val from = if (seekSong == track.key && now - seekAt < StepMemoryMs) seekTarget else snapshot.positionMs
        // Never right onto the end, which would read as the song finishing.
        seekTarget = (from + deltaMs).coerceIn(0L, (duration - EndMarginMs).coerceAtLeast(0L))
        seekSong = track.key
        seekAt = now
        if (seeking?.isActive != true) {
            seeking = scope.launch {
                var sent: Long? = null
                while (sent != seekTarget) {
                    val to = seekTarget
                    coordinator.seek(to)
                    sent = to
                }
            }
        }
        return true
    }

    /** The volume [delta] up (down, when negative), landing on the 5 % marks. */
    fun volumeBy(delta: Float) {
        unmuteTo = null
        setVolume(((current() + delta) / VolumeMark).roundToInt() * VolumeMark)
    }

    /** Mutes, or gives the volume back; true when it is now muted. */
    fun mute(): Boolean {
        val now = current()
        return if (now > 0.001f) {
            unmuteTo = now
            setVolume(0f)
            true
        } else {
            setVolume(unmuteTo ?: UnmuteDefault)
            unmuteTo = null
            false
        }
    }

    private fun current(): Float =
        if (clock() - volumeAt < StepMemoryMs) volumeTarget else coordinator.settings.value.volume

    private fun setVolume(value: Float) {
        volumeTarget = value.coerceIn(0f, 1f)
        volumeAt = clock()
        if (setting?.isActive != true) {
            setting = scope.launch {
                var sent: Float? = null
                while (sent != volumeTarget) {
                    val to = volumeTarget
                    coordinator.setVolume(to)
                    sent = to
                }
            }
        }
    }

    companion object {
        /** One press of an arrow: five seconds, as a video player steps. */
        const val SeekStepMs = 5_000L

        /** One press of Ctrl ↑ or ↓. */
        const val VolumeStep = 0.05f
        private const val VolumeMark = 0.05f

        /** A step this soon after the last goes on from it. */
        private const val StepMemoryMs = 1_500L

        /** How near the end a seek may land. */
        private const val EndMarginMs = 1_000L

        /** What Unmute gives back when Valen started muted and has nothing to give back. */
        private const val UnmuteDefault = 0.5f
    }
}
