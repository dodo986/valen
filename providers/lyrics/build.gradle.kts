plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

// The lyrics sources both apps use: LiriQo, configured Lyprio and LRCLIB, over OkHttp, plus the
// folder lyrics are kept in. A plain JVM library, so Android takes it as it is.
dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }
