/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Revera (https://github.com/caruno-git/Revera), base/host-android,
 * `LoginErrorDetails.kt` at c0b705c, which forks Myspoti/Orpheon (https://github.com/kota-zo/myspoti).
 * See NOTICE.
 */
package app.valen.host.android

/**
 * Why a Spotify sign-in is failing: the stage, the host, an HTTP status or a failure type.
 *
 * The record is deliberately narrow. It never holds a cookie or a `Set-Cookie` value, a header, a
 * request or response body, an e-mail address or a sign-in code: only a stage name from [STAGES]
 * and a short [detail] built by the callers from hosts, endpoint names and statuses. [record]
 * sanitises the detail down to a small alphabet anyway, so a careless caller cannot leak one.
 */
object LoginDiagnostics {
    private val STAGES = setOf(
        "not_reported", "opening", "transport", "network", "http", "ssl", "timeout", "blank",
        "player_bridge_timeout", "unsupported",
    )

    /** What a detail may contain; anything else (quotes, slashes, an `=` or a `;`) is dropped. */
    private val UNSAFE = Regex("[^A-Za-z0-9 ._:-]")

    @Volatile private var stage = "not_reported"
    @Volatile private var detail = ""

    /** Keeps the stage and a safe, short detail; the newest failure wins. */
    @Synchronized
    fun record(stage: String, detail: String = "") {
        this.stage = if (stage in STAGES) stage else "unknown"
        this.detail = UNSAFE.replace(detail, "").trim().take(MAX_DETAIL)
    }

    /** The failure as a type: an exception's class, or a keyword of a platform error's text. */
    fun failureType(error: Throwable?): String = error?.javaClass?.simpleName?.take(MAX_DETAIL) ?: "failed"

    /** A platform error's message is classified, never echoed (it may name the address). */
    fun failureType(message: String?): String {
        val m = (message ?: "").lowercase()
        return when {
            "timeout" in m || "timed out" in m || "timed_out" in m -> "timeout"
            "cancel" in m -> "canceled"
            "refused" in m || "connect" in m || "unreachable" in m -> "connection"
            "ssl" in m || "certificate" in m || "tls" in m -> "ssl"
            else -> "failed"
        }
    }

    /** One line for the log (never for the user's screen): stage and, when known, where it failed. */
    fun summary(): String = if (detail.isEmpty()) "Sign-in stage: $stage" else "Sign-in stage: $stage ($detail)"

    /** Tests only: back to the start. */
    internal fun reset() {
        stage = "not_reported"
        detail = ""
    }

    private const val MAX_DETAIL = 48
}