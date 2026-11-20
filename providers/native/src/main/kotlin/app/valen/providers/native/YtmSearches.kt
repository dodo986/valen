/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.myspoti.ytmusic.InnerTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject

/**
 * One YouTube Music search answers every question about it: its reply holds the songs, the
 * artists, the albums and the playlists together. So a query typed once is asked once, however
 * many of those the search page wants, and the reply is kept a minute for the next question.
 *
 * The request runs on the provider's own [scope], not on whoever asked first: a question given up
 * on (a timeout, a query typed over) never cancels it for the others waiting on the same reply.
 */
internal class YtmSearches(
    private val api: InnerTube,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val kept = LinkedHashMap<String, Pair<Deferred<JsonObject>, Long>>()

    suspend fun answer(query: String): JsonObject {
        val q = query.trim()
        val reply = synchronized(kept) {
            val now = clock()
            kept.entries.removeAll { now - it.value.second > KEEP_MS }
            kept[q]?.first ?: scope.async { api.search(q) }.also { asked ->
                kept[q] = asked to now
                while (kept.size > KEPT) kept.remove(kept.keys.first())
                // A failed reply is not kept: the next question asks again.
                asked.invokeOnCompletion { failure ->
                    if (failure != null) synchronized(kept) { if (kept[q]?.first === asked) kept.remove(q) }
                }
            }
        }
        return reply.await()
    }

    private companion object {
        const val KEEP_MS = 60_000L
        const val KEPT = 8
    }
}
