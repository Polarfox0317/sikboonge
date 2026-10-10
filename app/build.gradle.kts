plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.kotlin.plugin.serialization"); id("com.dropbox.dropshots") }

fun releaseSigningValue(propertyName: String, environmentName: String): String? =
    providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(environmentName).orNull

val releaseSigning = mapOf(
    "storeFile" to releaseSigningValue("sikboongeReleaseStoreFile", "SIKBOONGE_RELEASE_STORE_FILE"),
    "storePassword" to releaseSigningValue("sikboongeReleaseStorePassword", "SIKBOONGE_RELEASE_STORE_PASSWORD"),
    "keyAlias" to releaseSigningValue("sikboongeReleaseKeyAlias", "SIKBOONGE_RELEASE_KEY_ALIAS"),
    "keyPassword" to releaseSigningValue("sikboongeReleaseKeyPassword", "SIKBOONGE_RELEASE_KEY_PASSWORD"),
)
val hasAnyReleaseSigningValue = releaseSigning.values.any { !it.isNullOrBlank() }
val hasCompleteReleaseSigning = releaseSigning.values.all { !it.isNullOrBlank() }
require(!hasAnyReleaseSigningValue || hasCompleteReleaseSigning) {
    "Configure all four sikboonge release signing values, or leave all of them unset."
}

android {
    namespace = "io.github.polarfox0317.sikboonge"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.polarfox0317.sikboonge"
        minSdk = 26
        targetSdk = 37
        versionCode = 100
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    if (hasCompleteReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(requireNotNull(releaseSigning["storeFile"]))
            storePassword = requireNotNull(releaseSigning["storePassword"])
            keyAlias = requireNotNull(releaseSigning["keyAlias"])
            keyPassword = requireNotNull(releaseSigning["keyPassword"])
        }
    }
    buildTypes {
        getByName("release") {
            if (hasCompleteReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs.useLegacyPackaging = false }
    sourceSets["main"].assets.srcDir(rootProject.file("licenses"))
}
dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.5.0-beta01")
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")
    implementation("androidx.navigation3:navigation3-runtime:1.2.0")
    implementation("androidx.navigation3:navigation3-ui:1.2.0")
    implementation("androidx.navigationevent:navigationevent:1.1.1")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.1")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")
    // FFmpegKit's published maintained POM omits this upstream API dependency.
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.media3:media3-session:1.9.3")
    implementation("io.github.tdlib-android:core:0.1.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("com.dropbox.dropshots:dropshots:0.6.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

// Recording approved reference images is an explicit developer operation.
dropshots { recordOnFailure = false }
