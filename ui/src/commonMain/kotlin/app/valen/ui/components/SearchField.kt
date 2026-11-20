/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Motion
import app.valen.ui.typing
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * The search field: a 52dp capsule with a magnifier that never moves. It has no border at rest;
 * the edge appears while the caret is in it.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Songs, artists, albums",
    onClear: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val source = rememberInteraction()
    val focused by source.collectIsFocusedAsState()
    val edge by animateColorAsState(
        targetValue = if (focused) ValenColors.Text3 else ValenColors.GlassEdge.copy(alpha = 0f),
        animationSpec = Motion.press(),
        label = "searchEdge",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .typing()
            .clip(Radii.pillShape)
            .background(ValenColors.Fill)
            .border(1.dp, edge, Radii.pillShape)
            .padding(start = Space.step5, end = Space.step1),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = if (focused) ValenColors.Text else ValenColors.Text2,
            modifier = Modifier.size(22.dp),
        )
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.step3),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ValenColors.Text3,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ValenColors.Text),
                cursorBrush = SolidColor(ValenColors.Text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                interactionSource = source,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
        }
        if (value.isNotEmpty() && onClear != null) {
            IconTap(icon = Icons.Rounded.Close, label = "Clear search", onClick = onClear, iconSize = 20.dp)
        }
    }
}
