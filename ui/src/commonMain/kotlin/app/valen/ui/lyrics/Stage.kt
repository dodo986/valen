/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

/**
 * Where the song is among the pane's rows, from the song's time alone. The row the words stand on
 * is the last one begun, the line being sung is that row while it has not ended, and the rows still
 * living (a mark being drawn, a light trailing off) gather under it.
 *
 * Pure: the same time always answers the same thing, whatever happened before. That is what makes a
 * pause hold the stage still, a seek land it where the song now is and a seek back unwind it — and
 * why songs cannot leak effects into each other, since each song builds its own stage.
 */
internal class LyricStage(
    private val starts: LongArray,
    private val ends: LongArray,
    /** For each row, the song time past which it no longer needs the clock. */
    private val liveUntil: LongArray,
) {
    /** The row the words stand on: the last one begun, or -1 before the first. The credit at the
     * end is not a row the song can stand on. */
    fun active(t: Long): Int {
        var lo = 0
        var hi = starts.size - 2
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (starts[mid] <= t) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** The line being sung at [t], or -1 once the row on show has ended. */
    fun sung(t: Long, active: Int): Int = if (active >= 0 && active < ends.size && t < ends[active]) active else -1

    /**
     * The rows that still need the clock at [t]: the one being sung (if any) and the few past rows
     * under it whose marks or light have not finished.
     */
    fun trailing(t: Long, active: Int, sung: Int): IntRange {
        var first = active + 1
        for (i in active downTo maxOf(0, active - TRAILING_ROWS)) {
            if (i != sung && i < liveUntil.size && liveUntil[i] > t && starts[i] <= t) first = i
        }
        return if (first > active) IntRange.EMPTY else first..active
    }

    private companion object {
        /** Only the few rows just behind the song are ever kept alive by a mark or a light. */
        const val TRAILING_ROWS = 3
    }
}
