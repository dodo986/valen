/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import app.valen.ui.typing
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
import androidx.compose.material.icons.rounded.Link
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
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * The one-line field an address is typed or pasted into. It is the search field's shape and fill
 * with a link glyph instead of a magnifier, because it does the same thing to a different kind of
 * text: the edge appears only while the caret is in it.
 */
@Composable
fun LinkField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Paste a playlist link",
    focusRequester: FocusRequester? = null,
    /** The glyph at the start: a link by default (a key for an id, say). */
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.Link,
) {
    val source = rememberInteraction()
    val focused by source.collectIsFocusedAsState()
    val edge by animateColorAsState(
        targetValue = if (focused) ValenColors.Text3 else ValenColors.GlassEdge.copy(alpha = 0f),
        animationSpec = Motion.press(),
        label = "linkEdge",
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
            .padding(start = Space.step5, end = Space.step5),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (focused) ValenColors.Text else ValenColors.Text2,
            modifier = Modifier.size(22.dp),
        )
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .padding(start = Space.step3),
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
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (enabled) ValenColors.Text else ValenColors.Text3,
                ),
                cursorBrush = SolidColor(ValenColors.Text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                interactionSource = source,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
        }
    }
}
