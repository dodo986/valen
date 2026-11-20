/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.valen.ui.Route
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/** The places the panel lists, in order; Settings sits apart at the foot. */
private val Places = listOf(Route.Home, Route.Search, Route.Playlists, Route.NowPlaying, Route.Queue)

/** The gap between two places in the list. */
private val ItemGap = 2.dp

/**
 * Navigation for wide layouts, as a pane of liquid glass that floats over the page rather than a
 * column fixed to its edge: a screen's wash runs underneath it and shows through, blurred. With
 * labels at full width; [condensed] to an icon rail in between phone and desktop. The tag signs
 * the top; the profile sits at the foot, above Settings and the private mode switch.
 *
 * Where you are is a droplet of brighter glass behind the item. It pours from one place to the
 * next — drawn out along the way by its speed, settling with a small overshoot — rather than
 * jumping, so the panel reads as one piece of liquid.
 */
@Composable
fun Sidebar(
    current: Route,
    onNavigate: (Route) -> Unit,
    profileName: String,
    avatar: String?,
    privateMode: Boolean,
    onPrivateMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    condensed: Boolean = false,
) {
    GlassSurface(
        shape = Radii.artworkShape,
        corner = Radii.artwork * 0.62f,
        modifier = modifier
            .width(if (condensed) Layout.railWidth else Layout.sidebarWidth)
            .fillMaxHeight(),
    ) {
        Column(
            horizontalAlignment = if (condensed) Alignment.CenterHorizontally else Alignment.Start,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = Space.step3, vertical = Space.step4),
        ) {
            // The tag signs the panel. It writes itself when the app opens, and again when it is
            // pressed (it is also the way home).
            var replay by remember { mutableIntStateOf(0) }
            val tagSource = rememberInteraction()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .tap(
                        onClick = {
                            replay++
                            onNavigate(Route.Home)
                        },
                        shape = Radii.rowShape,
                        source = tagSource,
                    )
                    .padding(horizontal = if (condensed) Space.step1 else Space.step2, vertical = Space.step1),
            ) {
                Tag(
                    ink = ValenColors.Primary,
                    mist = ValenColors.Primary,
                    initial = condensed,
                    replay = replay,
                    source = tagSource,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Space.step4))

            PlaceList(current = current, condensed = condensed, onNavigate = onNavigate)

            Spacer(Modifier.weight(1f))

            ProfileChip(
                name = profileName,
                avatar = avatar,
                selected = current == Route.Profile,
                condensed = condensed,
                onClick = { onNavigate(Route.Profile) },
            )
            Spacer(Modifier.height(Space.step2))
            SidebarItem(
                route = Route.Settings,
                selected = current == Route.Settings,
                condensed = condensed,
                onClick = { onNavigate(Route.Settings) },
                droplet = true,
            )
            PrivateModeItem(checked = privateMode, condensed = condensed, onChange = onPrivateMode)
            // About, small, under it: who made this.
            val aboutOn = current == Route.About
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (condensed) Arrangement.Center else Arrangement.spacedBy(Space.step2),
                modifier = Modifier
                    .then(if (condensed) Modifier.size(Layout.hitTarget) else Modifier.fillMaxWidth().height(36.dp))
                    .tap(onClick = { onNavigate(Route.About) }, shape = Radii.pillShape, role = Role.Tab)
                    .padding(horizontal = if (condensed) 0.dp else Space.step4),
            ) {
                Icon(
                    imageVector = Route.About.icon,
                    contentDescription = if (condensed) Route.About.label else null,
                    tint = if (aboutOn) ValenColors.Primary else ValenColors.Text3,
                    modifier = Modifier.size(16.dp),
                )
                if (!condensed) {
                    Text(
                        Route.About.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (aboutOn) ValenColors.Primary else ValenColors.Text3,
                    )
                }
            }
        }
    }
}

/**
 * The places, over the droplet that marks the current one and a fainter lens that follows the
 * pointer from place to place. The droplet's two ends travel on different springs — the end
 * heading for the new place quick, the other slow — so it reaches, stretches, and gathers itself.
 */
@Composable
private fun PlaceList(current: Route, condensed: Boolean, onNavigate: (Route) -> Unit) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val item = with(density) { Layout.hitTarget.toPx() }
    val step = with(density) { (Layout.hitTarget + ItemGap).toPx() }
    val index = Places.indexOf(current)
    val top = remember { Animatable(maxOf(index, 0) * step) }
    val bottom = remember { Animatable(maxOf(index, 0) * step + item) }
    val shown by animateFloatAsState(
        targetValue = if (index >= 0) 1f else 0f,
        animationSpec = Motion.standard(),
        label = "dropletShown",
    )
    LaunchedEffect(index, step) {
        if (index < 0) return@LaunchedEffect
        val to = index * step
        if (reduced || top.value == to) {
            top.snapTo(to)
            bottom.snapTo(to + item)
            return@LaunchedEffect
        }
        val down = to > top.value
        coroutineScope {
            launch { top.animateTo(to, if (down) Motion.liquidTrail() else Motion.liquidLead()) }
            launch { bottom.animateTo(to + item, if (down) Motion.liquidLead() else Motion.liquidTrail()) }
        }
    }

    // The hover lens: which place the pointer is over, eased between places.
    var hover by remember { mutableIntStateOf(-1) }
    val lensY = remember { Animatable(0f) }
    val lensShown by animateFloatAsState(
        targetValue = if (hover >= 0 && hover != index && !reduced) 1f else 0f,
        animationSpec = Motion.standard(),
        label = "lensShown",
    )
    LaunchedEffect(hover) {
        if (hover < 0) return@LaunchedEffect
        if (lensShown == 0f) lensY.snapTo(hover * step) else lensY.animateTo(hover * step, Motion.liquid())
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(ItemGap),
        horizontalAlignment = if (condensed) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .pointerInput(step) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.type != PointerType.Mouse) continue
                        hover = if (event.type == PointerEventType.Exit) {
                            -1
                        } else {
                            (change.position.y / step).toInt().takeIf { it in Places.indices } ?: -1
                        }
                    }
                }
            }
            .drawBehind {
                val width = if (condensed) item else size.width
                val left = (size.width - width) / 2f
                if (lensShown > 0f) {
                    drawDroplet(
                        rect = Rect(Offset(left, lensY.value), Size(width, item)),
                        alpha = lensShown * 0.4f,
                    )
                }
                drawDroplet(
                    rect = Rect(left, top.value, left + width, bottom.value),
                    alpha = shown,
                )
            },
    ) {
        Places.forEach { route ->
            SidebarItem(
                route = route,
                selected = current == route,
                condensed = condensed,
                onClick = { onNavigate(route) },
                droplet = false,
            )
        }
    }
}

@Composable
private fun SidebarItem(
    route: Route,
    selected: Boolean,
    condensed: Boolean,
    onClick: () -> Unit,
    /** Draws its own droplet when selected; the place list draws one that travels instead. */
    droplet: Boolean,
) {
    val ink by animateColorAsState(
        targetValue = if (selected) ValenColors.OnContainer else ValenColors.Text2,
        animationSpec = Motion.standard(),
        label = "sidebarInk",
    )
    val own by animateFloatAsState(
        targetValue = if (droplet && selected) 1f else 0f,
        animationSpec = Motion.standard(),
        label = "ownDroplet",
    )
    val source = rememberInteraction()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (condensed) Arrangement.Center else Arrangement.spacedBy(Space.step3),
        modifier = Modifier
            .then(if (condensed) Modifier.size(Layout.hitTarget) else Modifier.fillMaxWidth().height(Layout.hitTarget))
            .drawBehind { if (own > 0f) drawDroplet(Rect(Offset.Zero, size), alpha = own) }
            .pressScale(source)
            .tap(
                onClick = onClick,
                shape = Radii.pillShape,
                rest = Color.Transparent,
                role = Role.Tab,
                source = source,
            )
            .padding(horizontal = if (condensed) 0.dp else Space.step3),
    ) {
        // The icon of the place you are in sits a touch larger, as if seen through the droplet.
        val lens by animateFloatAsState(if (selected) 1.08f else 1f, Motion.liquid(), label = "iconLens")
        Icon(
            imageVector = route.icon,
            contentDescription = if (condensed) route.label else null,
            tint = ink,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = lens
                    scaleY = lens
                },
        )
        if (!condensed) {
            Text(
                text = route.label,
                style = MaterialTheme.typography.labelLarge,
                color = ink,
                maxLines = 1,
            )
        }
    }
}

/**
 * Private mode, within reach at the foot of the panel: while it is on, nothing about what is
 * listened to is counted. An eye with a line through it, and the same ink-filled switch as the
 * settings pages; in the rail it rests as the glyph alone, tinted when it is on.
 */
@Composable
private fun PrivateModeItem(checked: Boolean, condensed: Boolean, onChange: (Boolean) -> Unit) {
    val ink by animateColorAsState(
        targetValue = if (checked) ValenColors.Primary else ValenColors.Text2,
        animationSpec = Motion.standard(),
        label = "privateInk",
    )
    if (condensed) {
        IconTap(
            icon = Icons.Rounded.VisibilityOff,
            label = if (checked) "Private mode on" else "Private mode off",
            onClick = { onChange(!checked) },
            tint = ink,
        )
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(Layout.hitTarget)
            .tap(onClick = { onChange(!checked) }, shape = Radii.pillShape, role = Role.Switch)
            .padding(start = Space.step3),
    ) {
        Icon(
            imageVector = Icons.Rounded.VisibilityOff,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = "Private mode",
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            maxLines = 1,
            modifier = Modifier.padding(start = Space.step3),
        )
        Spacer(Modifier.weight(1f))
        Toggle(checked = checked, onChange = onChange, label = "Private mode")
    }
}

/**
 * Who is listening, at the foot of the panel: the profile picture as a small round screen and
 * the name beside it. It leads to the profile. Before a picture is chosen it shows the place's
 * own icon; before a name, "Profile".
 */
@Composable
private fun ProfileChip(
    name: String,
    avatar: String?,
    selected: Boolean,
    condensed: Boolean,
    onClick: () -> Unit,
) {
    val own by animateFloatAsState(if (selected) 1f else 0f, Motion.standard(), label = "profileDroplet")
    val source = rememberInteraction()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (condensed) Arrangement.Center else Arrangement.spacedBy(Space.step3),
        modifier = Modifier
            .then(if (condensed) Modifier.size(Layout.hitTarget + Space.step1) else Modifier.fillMaxWidth().height(Layout.hitTarget + Space.step2))
            .drawBehind { if (own > 0f) drawDroplet(Rect(Offset.Zero, size), alpha = own) }
            .pressScale(source)
            .tap(onClick = onClick, shape = Radii.pillShape, role = Role.Tab, source = source)
            .padding(horizontal = if (condensed) 0.dp else Space.step1),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(ValenColors.Container),
        ) {
            if (avatar != null) {
                app.valen.ui.profile.PictureImage(uri = avatar, modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Icon(
                    imageVector = Route.Profile.icon,
                    contentDescription = if (condensed) Route.Profile.label else null,
                    tint = ValenColors.OnContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (!condensed) {
            Text(
                text = name.ifBlank { Route.Profile.label },
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) ValenColors.OnContainer else ValenColors.Text,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
