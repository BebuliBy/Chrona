import java.net.URI
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// Same strict syntax policy as ServerBaseUrl; a JVM parity test prevents drift.
// Keep invalid configuration out of BuildConfig/APK; errors never include the supplied value.
fun normalizeBootstrapBaseUrl(input: String): String? {
    // BEGIN CHRONA_BASE_URL_POLICY
    val text = input.trim()
    if (text.isEmpty() || text.length > 2048 || text.any { it.code <= 32 || it.code >= 127 } || '%' in text || '\\' in text) return null
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", true) || uri.isOpaque || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.port != -1 && uri.port !in 1..65535) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val labels = host.split('.')
    if (host.length > 253 || labels.size < 2 || !Regex("[a-z]{2,63}").matches(labels.last()) ||
        labels.any { !Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it) } ||
        labels.last() in setOf("localhost", "local", "internal", "lan", "home", "test", "invalid", "example", "onion", "arpa")) return null
    if (host == "api.telegram.org" || host.endsWith(".api.telegram.org")) return null
    val rawPath = uri.rawPath.orEmpty()
    if ("//" in rawPath) return null
    val path = rawPath.removeSuffix("/")
    if (path.isNotEmpty()) {
        if (!Regex("/[A-Za-z0-9._~/-]+").matches(path)) return null
        val credentials = setOf("token", "access_token", "refresh_token", "authorization", "session", "sessions", "login")
        if (path.drop(1).split('/').any { it.isEmpty() || it == "." || it == ".." ||
                it.lowercase(Locale.ROOT) in credentials || Regex("bot[0-9].*", RegexOption.IGNORE_CASE).matches(it) }) return null
    }
    return URI("https", null, host, if (uri.port == 443) -1 else uri.port, path, null, null).toASCIIString()
    // END CHRONA_BASE_URL_POLICY
}

val suppliedBootstrap = providers.gradleProperty("chronaApiBaseUrl")
    .orElse(providers.environmentVariable("CHRONA_API_BASE_URL")).getOrElse("")
val bootstrapBaseUrl = if (suppliedBootstrap.isBlank()) "" else normalizeBootstrapBaseUrl(suppliedBootstrap)
    ?: throw GradleException("Invalid Chrona API bootstrap configuration (value omitted)")

android {
    namespace = "by.bebuliby.chrona"
    compileSdk = 36
    defaultConfig {
        applicationId = "by.bebuliby.chrona"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.2-ux"
        // Strict policy accepts only ASCII URL characters safe in this Java String literal.
        buildConfigField("String", "CHRONA_API_BASE_URL", "\"$bootstrapBaseUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.room:room-testing:2.8.5")
    // JVM-only implementation of Android's org.json API for fake-transport tests; no APK dependency.
    testImplementation("org.json:json:20240303")
    // Conservative stable BOM compatible with the existing Kotlin 2.2.10 toolchain.
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
