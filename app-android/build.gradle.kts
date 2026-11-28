import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Lyprio's address and signing key are the builder's own, never in the source: a Gradle property,
// the environment, or the git-ignored local.properties (the names Metrolist uses). Without them the
// app asks LRCLIB alone.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun lyprioSetting(name: String): String =
    (providers.gradleProperty(name).orNull ?: System.getenv(name) ?: localProperties.getProperty(name)).orEmpty().trim()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// The key a phone build is signed with. An update installs over the app only when both are signed
// by the same key, so the release workflow and this machine must use one keystore: given as
// VALEN_KEYSTORE (a path) with VALEN_KEYSTORE_PASSWORD, VALEN_KEY_ALIAS and VALEN_KEY_PASSWORD, as
// Gradle properties, environment variables or local.properties. None given: the debug key, as
// before (and every release since uses it, so it is the key to give the workflow; see RELEASING.md).
fun signingSetting(name: String): String =
    (providers.gradleProperty(name).orNull ?: System.getenv(name) ?: localProperties.getProperty(name)).orEmpty().trim()
val releaseKeystore = signingSetting("VALEN_KEYSTORE")
val spotifyOnAndroid = (findProperty("valen.android.spotify") as String?)?.trim()?.toBoolean() ?: true

android {
    namespace = "app.valen.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.valen"
        minSdk = 29
        targetSdk = 36
        versionCode = rootProject.extra["valenVersionCode"] as Int
        versionName = rootProject.extra["valenVersion"] as String
        // Spotify on Android (gradle.properties, valen.android.spotify): in the normal build, as on
        // the desktop; a build can leave it out with -Pvalen.android.spotify=false.
        buildConfigField("boolean", "SPOTIFY", spotifyOnAndroid.toString())
        // Where updates come from: the releases of this repository (VALEN_UPDATE_REPO overrides).
        buildConfigField("String", "UPDATE_REPO", quoted(signingSetting("VALEN_UPDATE_REPO").ifEmpty { "dodo986/valen" }))
        buildConfigField("String", "LYPRIO_BASE_URL", quoted(lyprioSetting("LYPRIO_BASE_URL")))
        buildConfigField("String", "LYPRIO_HMAC_KEY", quoted(lyprioSetting("LYPRIO_HMAC_KEY")))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (releaseKeystore.isNotEmpty()) {
            create("valen") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = signingSetting("VALEN_KEYSTORE_PASSWORD")
                keyAlias = signingSetting("VALEN_KEY_ALIAS")
                keyPassword = signingSetting("VALEN_KEY_PASSWORD").ifEmpty { signingSetting("VALEN_KEYSTORE_PASSWORD") }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // The build to use on a phone. A debug build is debuggable: ART runs it with its
        // optimisations held back and Compose with its checks on, which made every screen lag
        // (lyrics on the emulator: 20 ms a frame in debug). R8 shrinks and optimises the libraries
        // (Compose above all); Valen's own classes are kept whole (see proguard-rules.pro). Signed
        // with the release keystore when one is given, else the debug key (see signingSetting).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("valen") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":providers:spotify"))
    implementation(project(":providers:ytmusic"))
    implementation(project(":providers:soundcloud"))
    implementation(project(":providers:native"))
    implementation(project(":providers:lyrics"))
    implementation(project(":libs:update"))
    implementation(project(":host:android"))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // Applies the libraries' own startup profiles (Compose ships them) on a sideloaded install,
    // so their hot code is compiled ahead instead of interpreted for the first minutes.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
