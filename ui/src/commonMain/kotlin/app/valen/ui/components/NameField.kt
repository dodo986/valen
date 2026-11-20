/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import app.valen.ui.typing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.valen.ui.theme.ValenColors

/**
 * A name typed straight onto the page, with no box around it: the text in its own style, "Name
 * it" until something is typed, and the cursor in the primary tone. Enter (or the keyboard's Done)
 * keeps it; Escape lets it go.
 */
@Composable
fun NameField(
    value: String,
    onValue: (String) -> Unit,
    onDone: () -> Unit,
    style: TextStyle,
    label: String,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
    placeholder: String = "Name it",
    autoFocus: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
    BasicTextField(
        value = value,
        onValueChange = { onValue(it.take(80)) },
        singleLine = true,
        textStyle = style.copy(color = ValenColors.Text),
        cursorBrush = SolidColor(ValenColors.Primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .typing()
            .focusRequester(focus)
            .semantics { contentDescription = label }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { onDone(); true }
                    Key.Escape -> { onCancel(); true }
                    else -> false
                }
            },
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = ValenColors.Text3), maxLines = 1)
                field()
            }
        },
    )
}
