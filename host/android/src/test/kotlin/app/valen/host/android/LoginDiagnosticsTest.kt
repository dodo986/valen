/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Revera (https://github.com/caruno-git/Revera), base/host-android,
 * `LoginErrorDetailsTest.kt` at c0b705c. See NOTICE.
 */
package app.valen.host.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginDiagnosticsTest {

    @After fun clean() = LoginDiagnostics.reset()

    @Test fun `a stage and a host are kept`() {
        LoginDiagnostics.record("http", "accounts.spotify.com 503")
        assertEquals("Sign-in stage: http (accounts.spotify.com 503)", LoginDiagnostics.summary())
    }

    // The record must never become a place a secret can land, even through a careless caller.
    @Test fun `a detail is stripped down to the safe alphabet`() {
        LoginDiagnostics.record("network", "cookie sp_dc=abc123; x-requested-with: app.valen; https://x/?code=987654")
        val summary = LoginDiagnostics.summary()
        assertTrue("no '=' survives", "=" !in summary)
        assertTrue("no ';' survives", ";" !in summary)
        assertTrue("no quotes survive", "\"" !in summary)
        assertTrue("no slashes survive", "/" !in summary)
        assertTrue("still names the stage", summary.startsWith("Sign-in stage: network"))
    }

    @Test fun `an unknown stage is not taken on trust`() {
        LoginDiagnostics.record("code-987654", "")
        assertEquals("Sign-in stage: unknown", LoginDiagnostics.summary())
    }

    // Failure types are classes or keywords, never the platform message (it names the address).
    @Test fun `a platform message is classified, not echoed`() {
        assertEquals("timeout", LoginDiagnostics.failureType("net::ERR_TIMED_OUT"))
        assertEquals("ssl", LoginDiagnostics.failureType("certificate verify failed"))
        assertEquals("canceled", LoginDiagnostics.failureType("request canceled"))
        assertEquals("failed", LoginDiagnostics.failureType("https://accounts.spotify.com/login?code=987654"))
        assertEquals("IOException", LoginDiagnostics.failureType(java.io.IOException("boom")))
    }
}