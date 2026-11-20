/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.artwork.toSmallPixelMap
import app.valen.ui.artwork.tonesFrom
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.ValenColors
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Artwork is never shown as a spinner or a shimmer: an empty frame in the skeleton grey until the
 * image arrives, then a 320ms crossfade.
 *
 * With [measure] on, the same load also works out the artwork's tones for the screen's wash and
 * the player's tint. Only covers that tint the current song or opened page ask for that.
 */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = Radii.coverShape,
    contentDescription: String? = null,
    iconSize: Dp = 22.dp,
    animate: Boolean = true,
    measure: Boolean = false,
    /** Called once the picture is there to be drawn (or will not come): a poster waits for it. */
    onReady: (() -> Unit)? = null,
) {
    val context = LocalPlatformContext.current
    val reduced = LocalReducedMotion.current
    val request = remember(url, animate, reduced) {
        ImageRequest.Builder(context)
            .data(url)
            .apply { if (animate && !reduced) crossfade(Motion.ArtworkCrossfadeMs) }
            .build()
    }

    // The tones are computed from the decoded image, never from a callback: an `onSuccess` runs
    // after the composable may have left (a new query, a new screen) and launching a coroutine
    // from a scope that outlived its composition throws inside the composition itself.
    var decoded by remember(url) { mutableStateOf<coil3.Image?>(null) }
    if (measure) {
        LaunchedEffect(url, decoded) {
            val image = decoded ?: return@LaunchedEffect
            val key = url ?: return@LaunchedEffect
            if (ArtworkPalette.known(key)) return@LaunchedEffect
            val tones = withContext(Dispatchers.Default) {
                image.toSmallPixelMap(MeasurePx)?.let { tonesFrom(it) }
            }
            ArtworkPalette.put(key, tones ?: ArtworkPalette.Neutral)
        }
    }

    Box(
        modifier = modifier.clip(shape).background(ValenColors.Skeleton),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            if (onReady != null) LaunchedEffect(Unit) { onReady() }
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = contentDescription,
                tint = ValenColors.Text3,
                modifier = Modifier.size(iconSize),
            )
        } else {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                // A 640px cover drawn at 48dp is a 13x reduction; plain bilinear turns that to noise.
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.matchParentSize(),
                onSuccess = { success ->
                    if (measure) decoded = success.result.image
                    onReady?.invoke()
                },
                onError = { onReady?.invoke() },
            )
        }
    }
}

/** The picture is read this small: plenty to tell the colour of each half. */
private const val MeasurePx = 32

/** A square cover at a fixed size, for rows, tiles and the player. */
@Composable
fun ArtworkSquare(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = Radii.coverShape,
    contentDescription: String? = null,
    measure: Boolean = false,
) {
    Artwork(
        url = url,
        contentDescription = contentDescription,
        shape = shape,
        measure = measure,
        modifier = modifier.size(size),
    )
}
