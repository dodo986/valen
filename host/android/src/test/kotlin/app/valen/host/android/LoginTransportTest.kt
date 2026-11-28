/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Revera (https://github.com/caruno-git/Revera), base/host-android,
 * `LoginTransportTest.kt` at c0b705c. See NOTICE.
 */
package app.valen.host.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginTransportTest {

    // The relay is a narrow contract: the two e-mail-code endpoints, on the exact account host only.
    @Test fun `only the code requests on the exact accounts host are relayed`() {
        assertTrue(LoginTransport.allowed("POST", "https://accounts.spotify.com/login/code/send?flow=x"))
        assertTrue(LoginTransport.allowed("POST", "https://accounts.spotify.com/login/code/verify"))
        assertTrue(LoginTransport.allowed("POST", "https://accounts.spotify.com:443/login/code/verify"))
        assertFalse(LoginTransport.allowed("GET", "https://accounts.spotify.com/login/code/send"))
        assertFalse(LoginTransport.allowed("POST", "http://accounts.spotify.com/login/code/send"))
        assertFalse(LoginTransport.allowed("POST", "https://accounts.spotify.com.evil.test/login/code/send"))
        assertFalse(LoginTransport.allowed("POST", "https://evil.test/accounts.spotify.com/login/code/send"))
        assertFalse(LoginTransport.allowed("POST", "https://accounts.spotify.com:8443/login/code/send"))
        assertFalse(LoginTransport.allowed("POST", "https://user@accounts.spotify.com/login/code/send"))
        assertFalse(LoginTransport.allowed("POST", "https://accounts.spotify.com/login/password"))
        assertFalse(LoginTransport.allowed("POST", "https://accounts.spotify.com/login/code/send/extra"))
        assertFalse(LoginTransport.allowed("POST", "not a url"))
    }

    // One Server answer with two session cookies: both must survive, in order, with every attribute.
    @Test fun `every set-cookie is kept exactly as the server wrote it`() {
        val headers = listOf(
            "Content-Type" to "application/json",
            "Set-Cookie" to "sp_dc=abc123; Path=/; Domain=.spotify.com; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Secure; HttpOnly; SameSite=Lax",
            "Cache-Control" to "no-store",
            "set-cookie" to "sp_key=xyz; Path=/; Max-Age=3600; Secure; HttpOnly",
        )
        assertEquals(
            listOf(
                "sp_dc=abc123; Path=/; Domain=.spotify.com; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Secure; HttpOnly; SameSite=Lax",
                "sp_key=xyz; Path=/; Max-Age=3600; Secure; HttpOnly",
            ),
            LoginTransport.setCookies(headers),
        )
    }

    @Test fun `an answer without cookies keeps none`() {
        assertEquals(emptyList<String>(), LoginTransport.setCookies(listOf("Content-Type" to "application/json")))
    }

    // The step is named by its path; a query (which may carry a flow id) is never in the name.
    @Test fun `the logged step names the endpoint, never the query`() {
        assertEquals("code-send", LoginTransport.endpointOf("https://accounts.spotify.com/login/code/send?flow_ctx=secret"))
        assertEquals("code-verify", LoginTransport.endpointOf("https://accounts.spotify.com/login/code/verify"))
        assertEquals("code-request", LoginTransport.endpointOf("https://accounts.spotify.com/login/password"))
    }
}