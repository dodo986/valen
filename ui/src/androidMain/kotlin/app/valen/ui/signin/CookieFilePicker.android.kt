/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.signin

import androidx.compose.runtime.Composable

@Composable
actual fun rememberCookieFilePicker(onPicked: (String) -> Unit): () -> Unit = {}
