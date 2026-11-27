/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.signin

import androidx.compose.runtime.Composable

/**
 * Opens the system file picker for a Netscape `cookies.txt` or cookie JSON export and passes the
 * chosen file path to [onPicked]. No-op when the user cancels or on platforms that do not use
 * external cookie files.
 */
@Composable
expect fun rememberCookieFilePicker(onPicked: (String) -> Unit): () -> Unit
