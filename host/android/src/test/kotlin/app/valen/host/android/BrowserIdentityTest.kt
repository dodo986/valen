/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserIdentityTest {

    // Real desktop Chrome headers: the grease brand, its version and the order follow the major.
    @Test fun `brands match what Chrome itself sends`() {
        val real = mapOf(
            116 to "\"Chromium\";v=\"116\", \"Not)A;Brand\";v=\"24\", \"Google Chrome\";v=\"116\"",
            120 to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
            124 to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
            131 to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        )
        real.forEach { (major, header) -> assertEquals(header, BrowserIdentity(major, 1, 1, "6.8.0").secChUa) }
    }

    // The 2026-11-16 regression: Chrome/14x in the user agent, "Chromium 133" in the hints.
    @Test fun `the user agent and the hints name the same version`() {
        val id = BrowserIdentity.resolve("142.0.7444.171", "6.6.9")
        assertTrue(id.userAgent.contains("Chrome/142.0.0.0 "))
        assertEquals("142.0.7444.171", id.fullVersion)
        assertTrue(id.brands.filter { it.name == "Chromium" || it.name == "Google Chrome" }.all { it.major == "142" })
        assertEquals(setOf("Chromium", "Google Chrome"), id.brands.map { it.name }.filter { !it.startsWith("Not") }.toSet())
    }

    @Test fun `an unreadable WebView version falls back to a plausible Chrome`() {
        val id = BrowserIdentity.resolve("not a version", "nonsense")
        assertTrue(id.major in 100..300 && id.build > 0)
        assertEquals(BrowserIdentity.KERNELS.first(), id.kernel)
        assertNull(BrowserIdentity.parseEngine("141.0"))
        assertNull(BrowserIdentity.parseEngine(null))
    }

    // The sign-in page: the phone's own Chrome, with the WebView's markers off and hints that agree.
    @Test fun `the phone's user agent and its hints name the same Chrome`() {
        val webView = "Mozilla/5.0 (Linux; Android 15; Pixel 7 Build/AP3A.240905.015) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/142.0.7444.171 Mobile Safari/537.36; wv"
        val mobile = MobileIdentity.of(webView, "15", "Pixel 7")

        // Exactly Chrome on the phone: no "; wv", no " Version/4.0", still mobile Android.
        assertEquals(
            "Mozilla/5.0 (Linux; Android 15; Pixel 7 Build/AP3A.240905.015) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.7444.171 Mobile Safari/537.36",
            mobile.userAgent,
        )
        assertTrue(mobile.userAgent.contains("Android") && mobile.userAgent.contains("Mobile Safari"))
        assertEquals(142, mobile.major)
        assertEquals("142.0.7444.171", mobile.fullVersion)

        // The hints name that same engine, as mobile, on this device — never the desktop player's.
        val named = mobile.hints.brands.filter { it.name == "Chromium" || it.name == "Google Chrome" }
        assertEquals(setOf("Chromium", "Google Chrome"), named.map { it.name }.toSet())
        assertTrue(named.all { it.major == "142" && it.fullVersion == "142.0.7444.171" })
        assertTrue(mobile.hints.brands.any { it.name.startsWith("Not") })
        assertEquals(mobile.secChUa, mobile.hints.brands.joinToString(", ") { "\"${it.name}\";v=\"${it.major}\"" })
        assertTrue(mobile.hints.mobile)
        assertEquals("Android", mobile.hints.platform)
        assertEquals("15", mobile.hints.platformVersion)
        assertEquals("Pixel 7", mobile.hints.model)
        assertEquals("arm", mobile.hints.architecture)
        assertEquals(64, mobile.hints.bitness)
    }

    // A user agent with no readable version still gets one identity: the fallback engine's, in both.
    @Test fun `an unreadable mobile engine keeps the user agent and the hints together`() {
        val mobile = MobileIdentity.of(
            "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Mobile Safari/537.36; wv",
            "14",
            "Pixel 6",
        )
        assertEquals(BrowserIdentity.FALLBACK_ENGINE, mobile.fullVersion)
        assertEquals(BrowserIdentity.FALLBACK_ENGINE.substringBefore('.').toInt(), mobile.major)
        assertEquals(
            mobile.major.toString(),
            mobile.hints.brands.first { it.name == "Chromium" }.major,
        )
        assertTrue("the markers are gone", "; wv" !in mobile.userAgent && "Version/4.0" !in mobile.userAgent)
    }
}
