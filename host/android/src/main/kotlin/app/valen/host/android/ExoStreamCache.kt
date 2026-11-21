/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.android

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.valen.audio.DiskAudioCache
import app.valen.audio.ResolvedStream
import app.valen.audio.StreamCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's songs on disk: ExoPlayer's own cache, filed under the song's key (not its address,
 * which expires). A song is kept as it streams — the first listen fetches it once, not twice —
 * and the song likely next is fetched ahead ([keep] with `playing = false`). A song kept whole
 * plays with no lookup and no network ([offline]).
 *
 * HLS streams (some SoundCloud tracks) are played but not kept: their segments are addressed one
 * by one and change with every lookup, so keeping them would only fill the disk.
 */
@OptIn(UnstableApi::class)
class ExoStreamCache(
    context: Context,
    maxBytes: Long,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) : StreamCache {
    private val app = context.applicationContext

    /** One per folder per process, as ExoPlayer requires: the app makes one and shares it. */
    val cache: SimpleCache = SimpleCache(
        File(app.cacheDir, "songs"),
        LeastRecentlyUsedCacheEvictor(maxBytes),
        StandaloneDatabaseProvider(app),
    )

    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val writers = ConcurrentHashMap<String, CacheWriter>()
    private val slots = Semaphore(1)

    /** Reads what is kept, keeps what it reads; [headers] go with every request to the network. */
    fun dataSources(headers: Map<String, String>): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                DefaultDataSource.Factory(app, OkHttpDataSource.Factory(http).setDefaultRequestProperties(headers)),
            )
            // A damaged span is read from the network instead of failing the song.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    override fun offline(key: String): ResolvedStream? {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0 || !cache.isCached(key, 0, length)) return null
        return ResolvedStream(url = "$SCHEME://${DiskAudioCache.safe(key)}", cacheKey = key)
    }

    override fun keep(key: String, stream: ResolvedStream, playing: Boolean) {
        // Playing: the player keeps it as it goes, and a fetch ahead of the same song gives way
        // (the two would only wait on each other's part of the file). HLS is not kept at all.
        if (playing) {
            writers.remove(key)?.cancel()
            return
        }
        if (DiskAudioCache.isHls(stream.url)) return
        if (!busy.add(key)) return
        scope.launch(Dispatchers.IO) {
            try {
                slots.withPermit {
                    android.util.Log.d("ValenAudio", "keeping $key ahead")
                    fetchAhead(key, stream)
                    android.util.Log.d("ValenAudio", "kept $key")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not kept this time: it streams when it plays.
                android.util.Log.d("ValenAudio", "could not keep $key ahead: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy.remove(key)
            }
        }
    }

    /**
     * The song in [CHUNK_BYTES] ranges (YouTube's servers slow one long open-ended request to about
     * the speed it plays at), until a range comes back short: that is the end, and the song's
     * length is written down, so [offline] can tell it is whole.
     */
    private fun fetchAhead(key: String, stream: ResolvedStream) {
        // A plain source, not the downloading one: that one waits for any part of the file the
        // player is writing, and a song started meanwhile held it for good.
        val source = dataSources(stream.headers).createDataSource()
        var position = 0L
        while (position < MAX_SONG_BYTES) {
            val knownLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (knownLength > MAX_SONG_BYTES) return
            val spec = DataSpec.Builder().setUri(stream.url).setKey(key).setHttpRequestHeaders(stream.headers)
                .setPosition(position).setLength(CHUNK_BYTES).build()
            val writer = CacheWriter(source, spec, null, null)
            writers[key] = writer
            try {
                writer.cache()
            } catch (e: java.io.IOException) {
                // Asking past the end of a file whose length was a whole number of ranges.
                if (position > 0 && cache.getCachedBytes(key, position, CHUNK_BYTES) == 0L && e !is java.io.InterruptedIOException) break
                throw e
            } finally {
                writers.remove(key, writer)
            }
            val got = cache.getCachedBytes(key, position, CHUNK_BYTES)
            if (got < CHUNK_BYTES) {
                position += got
                break
            }
            position += CHUNK_BYTES
        }
        if (position <= 0L) throw java.io.IOException("nothing came back")
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        // Reaching the byte cap is not proof that an unknown-length stream is complete.
        if (length <= 0L && position < MAX_SONG_BYTES) {
            val mutations = androidx.media3.datasource.cache.ContentMetadataMutations()
            androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(mutations, position)
            cache.applyContentMetadataMutations(key, mutations)
        }
    }

    override fun forget(key: String) {
        runCatching { cache.removeResource(key) }
    }

    companion object {
        /** The address of a song played from the cache alone (never sent anywhere). */
        const val SCHEME = "valen-cache"
        private const val CHUNK_BYTES = 1L * 1024 * 1024
        /** About an hour of audio; longer mixes are streamed, not fetched ahead. */
        private const val MAX_SONG_BYTES = 64L * 1024 * 1024
    }
}
