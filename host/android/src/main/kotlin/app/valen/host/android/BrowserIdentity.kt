/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Orpheon (https://github.com/kota-zo/Orpheon), base/host-android, `Fingerprint.kt`.
 * See NOTICE.
 */
package app.valen.host.android

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.File
import kotlin.random.Random

/**
 * The desktop Chrome the app's WebViews present: one user agent and the client hints that agree
 * with it, both from the WebView engine's real version.
 *
 * They must agree. The user agent once named the engine's version (Chrome/14x) while the hints
 * still said "Chromium 133" with no "Google Chrome" brand: a browser that contradicts itself, which
 * Spotify's sign-in answered with "Oops! Something went wrong" and Google's with "This browser or
 * app may not be secure" (2026-11-16). The brand list, its grease brand and their order are what
 * Chromium itself derives from the major version.
 */
data class BrowserIdentity(val major: Int, val build: Int, val patch: Int, val kernel: String) {

    /** Reduced, like every desktop Chrome since the user-agent reduction: `Chrome/<major>.0.0.0`. */
    val userAgent: String
        get() = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36"

    /** The full version only the client hints carry. */
    val fullVersion: String get() = "$major.0.$build.$patch"

    val brands: List<Brand> get() = brandsOf(major, fullVersion)

    /** The `sec-ch-ua` header these brands make, as Chromium writes it. */
    val secChUa: String get() = brands.joinToString(", ") { "\"${it.name}\";v=\"${it.major}\"" }

    data class Brand(val name: String, val major: String, val fullVersion: String)

    /**
     * Puts this identity on a WebView's [settings]: the user agent, the client hints, and no
     * `X-Requested-With` (no browser sends it) where the WebView still lets an app drop it.
     */
    fun applyTo(settings: WebSettings) {
        settings.userAgentString = userAgent
        val xrw = WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)
        if (xrw) {
            runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet()) }
                .onFailure { Log.w(LOG, "identity: X-Requested-With not dropped: $it") }
        }
        val hints = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        if (hints) {
            runCatching {
                WebSettingsCompat.setUserAgentMetadata(settings, UserAgentMetadata.Builder()
                    .setBrandVersionList(brands.map {
                        UserAgentMetadata.BrandVersion.Builder()
                            .setBrand(it.name).setMajorVersion(it.major).setFullVersion(it.fullVersion).build()
                    })
                    .setFullVersion(fullVersion)
                    .setPlatform("Linux")
                    .setPlatformVersion(kernel)
                    .setArchitecture("x86")
                    .setBitness(64)
                    .setMobile(false)
                    .setModel("")
                    .setWow64(false)
                    .build())
            }.onFailure { Log.w(LOG, "identity: client hints not set: $it") }
        }
        // What the WebView really took, once per view: a sign-in refused as "not secure" starts here.
        val took = if (hints) runCatching { WebSettingsCompat.getUserAgentMetadata(settings).brandVersionList.joinToString { "${it.brand} ${it.majorVersion}" } }
            .getOrElse { "unreadable: $it" } else "unsupported"
        Log.i(LOG, "identity: Chrome $major, hints [$took], drop X-Requested-With ${if (xrw) "supported" else "unsupported"}")
    }

    companion object {
        private const val LOG = "Valen"

        /** Linux versions a desktop Chrome reports; each install draws one and keeps it. */
        val KERNELS = listOf("6.8.0", "6.7.2", "6.6.9", "6.5.7", "6.9.1", "6.4.12")

        /** Used only when the WebView's version cannot be read. */
        internal const val FALLBACK_ENGINE = "142.0.7444.0"

        // Chromium's GREASE (components/embedder_support/user_agent_utils.cc).
        private val GREASE_CHARS = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        private val GREASE_VERSIONS = listOf("8", "99", "24")
        private val ORDERS = listOf(
            intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
            intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0),
        )

        fun brandsOf(major: Int, fullVersion: String): List<Brand> {
            val greaseVersion = GREASE_VERSIONS[major % GREASE_VERSIONS.size]
            val grease = Brand(
                "Not${GREASE_CHARS[major % GREASE_CHARS.size]}A${GREASE_CHARS[(major + 1) % GREASE_CHARS.size]}Brand",
                greaseVersion,
                "$greaseVersion.0.0.0",
            )
            val order = ORDERS[major % ORDERS.size]
            val out = arrayOfNulls<Brand>(3)
            out[order[0]] = grease
            out[order[1]] = Brand("Chromium", "$major", fullVersion)
            out[order[2]] = Brand("Google Chrome", "$major", fullVersion)
            return out.map { it!! }
        }

        /** A Chrome version string ("141.0.7390.122") as (major, build, patch); null if it is not one. */
        fun parseEngine(version: String?): Triple<Int, Int, Int>? {
            val parts = version?.trim()?.split('.')?.map { it.toIntOrNull() } ?: return null
            if (parts.size != 4 || parts.any { it == null }) return null
            val (major, _, build, patch) = parts.map { it!! }
            return if (major in 100..300 && build > 0) Triple(major, build, patch) else null
        }

        /** The identity on WebView engine [engineVersion] with this install's [kernel]. */
        fun resolve(engineVersion: String?, kernel: String): BrowserIdentity {
            val (major, build, patch) = parseEngine(engineVersion) ?: parseEngine(FALLBACK_ENGINE)!!
            return BrowserIdentity(major, build, patch, kernel.takeIf { it in KERNELS } ?: KERNELS.first())
        }

        @Volatile private var cached: BrowserIdentity? = null

        /** This install's identity, read once per run (no browser changes version between two requests). */
        fun of(context: Context): BrowserIdentity = cached ?: synchronized(this) {
            cached ?: run {
                val app = context.applicationContext
                val engine = runCatching { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(app)?.versionName }.getOrNull()
                val file = File(app.filesDir, "browser-kernel.txt")
                val kernel = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it in KERNELS }
                    ?: KERNELS[Random.nextInt(KERNELS.size)].also { k -> runCatching { file.writeText(k) } }
                resolve(engine, kernel)
            }.also { cached = it }
        }
    }
}

/**
 * The phone's own Chrome, for Spotify's sign-in page.
 *
 * The Web Player wants a desktop browser (on a phone user agent it only offers to open the app), but
 * Spotify's account pages are built for the phone's browser: the sign-in is shown as the device's own
 * mobile Chrome, and once Spotify hands the session back to `open.spotify.com` the page switches to
 * the desktop player identity again (Revera's 1.0.2 sign-in fix, still live at c0b705c).
 *
 * The user agent and the client hints are one identity and must agree: a reduced mobile Chrome user
 * agent beside hints that name the same major version, `mobile=true`, Android and the device model.
 * Desynchronising them is the documented "Oops! Something went wrong" / "This browser or app may not
 * be secure" trap (`BrowserIdentity`'s header). Pinned by `BrowserIdentityTest`.
 */
data class MobileIdentity(
    val userAgent: String,
    val major: Int,
    val fullVersion: String,
    val platformVersion: String,
    val model: String,
) {
    val brands: List<BrowserIdentity.Brand> get() = BrowserIdentity.brandsOf(major, fullVersion)

    /** The `sec-ch-ua` header these brands make, as Chromium writes it. */
    val secChUa: String get() = brands.joinToString(", ") { "\"${it.name}\";v=\"${it.major}\"" }

    /** Everything [applyTo] puts on the settings, as one value the test can check against the user agent. */
    val hints: Hints get() = Hints(
        brands = brands,
        fullVersion = fullVersion,
        platform = "Android",
        platformVersion = platformVersion,
        architecture = "arm",
        bitness = 64,
        mobile = true,
        model = model,
    )

    /** Puts this identity on the sign-in page's [settings]: the mobile user agent and agreeing hints. */
    fun applyTo(settings: WebSettings) {
        settings.userAgentString = userAgent
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        runCatching {
            WebSettingsCompat.setUserAgentMetadata(settings, UserAgentMetadata.Builder()
                .setBrandVersionList(brands.map {
                    UserAgentMetadata.BrandVersion.Builder()
                        .setBrand(it.name).setMajorVersion(it.major).setFullVersion(it.fullVersion).build()
                })
                .setFullVersion(fullVersion)
                .setPlatform(hints.platform)
                .setPlatformVersion(hints.platformVersion)
                .setArchitecture(hints.architecture)
                .setBitness(hints.bitness)
                .setMobile(hints.mobile)
                .setModel(hints.model)
                .setWow64(false)
                .build())
        }.onFailure { Log.w(LOG, "mobile identity: client hints not set: $it") }
    }

    /** The client hints, whole. */
    data class Hints(
        val brands: List<BrowserIdentity.Brand>,
        val fullVersion: String,
        val platform: String,
        val platformVersion: String,
        val architecture: String,
        val bitness: Int,
        val mobile: Boolean,
        val model: String,
    )

    companion object {
        private const val LOG = "Valen"
        private val CHROME = Regex("Chrome/([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+)")

        /**
         * The identity behind a WebView's own default user agent (the phone's Chrome), with the two
         * markers an embedded WebView adds taken off: `; wv` and ` Version/4.0`. The major the hints
         * will name is read from that same user agent, so neither can drift from the other.
         */
        fun of(defaultUserAgent: String, platformVersion: String, model: String): MobileIdentity {
            val ua = defaultUserAgent.replace("; wv", "").replace(" Version/4.0", "").trim()
            val full = CHROME.find(ua)?.groupValues?.get(1) ?: BrowserIdentity.FALLBACK_ENGINE
            val major = full.substringBefore('.').toIntOrNull()
                ?: BrowserIdentity.FALLBACK_ENGINE.substringBefore('.').toInt()
            return MobileIdentity(ua, major, full, platformVersion, model)
        }
    }
}
