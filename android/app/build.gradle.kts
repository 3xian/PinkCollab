import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Signing values come from the environment only when all four are set (CI).
// Otherwise the build uses the machine-local key in ~/.pinkcollab-signing, which
// is not a Gradle cache and is not tracked. A partial environment does not hide
// that key: a leftover ANDROID_* variable must not disable a working install.
val signingDirectory = File(System.getProperty("user.home"), ".pinkcollab-signing")
val localKeystore = signingDirectory.resolve("pinkcollab-release.p12")
val localPropertiesFile = signingDirectory.resolve("pinkcollab-release.properties")
val localSigning: Map<String, String>? =
    if (localKeystore.isFile && localPropertiesFile.isFile) {
        val stored = Properties().apply { localPropertiesFile.inputStream().use { load(it) } }
        mapOf(
            "keystore" to localKeystore.absolutePath,
            "storePassword" to stored.getProperty("ANDROID_KEYSTORE_PASSWORD"),
            "keyAlias" to stored.getProperty("ANDROID_KEY_ALIAS"),
            "keyPassword" to stored.getProperty("ANDROID_KEY_PASSWORD"),
        ).takeIf { values -> values.values.none { it.isNullOrBlank() } }?.mapValues { it.value!! }
    } else {
        null
    }
val environment =
    mapOf(
        "keystore" to providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull,
        "storePassword" to providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull,
        "keyAlias" to providers.environmentVariable("ANDROID_KEY_ALIAS").orNull,
        "keyPassword" to providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull,
    ).mapValues { (_, value) -> value?.takeIf { it.isNotBlank() } }
val environmentComplete = environment.values.none { it.isNullOrBlank() }
val releaseSigning: Map<String, String>? = if (environmentComplete) {
    environment.mapValues { it.value!! }
} else {
    localSigning
}
val signingReady = releaseSigning != null
val signingFailure =
    if (environment.values.any { it != null } && !environmentComplete) {
        "Android release signing environment is partial. Set ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD together, or unset them and install ~/.pinkcollab-signing (docs/npm-release.md)."
    } else if (localKeystore.isFile && localSigning == null) {
        "Found ${localKeystore.absolutePath} but ${localPropertiesFile.name} is missing ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, or ANDROID_KEY_PASSWORD."
    } else {
        "Refusing to build an unsigned release. Install ~/.pinkcollab-signing/pinkcollab-release.p12 and pinkcollab-release.properties (docs/npm-release.md)."
    }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.pinkcollab"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.pinkcollab"
        minSdk = 26
        targetSdk = 36
        versionCode = 2000
        versionName = "0.2.0"
    }
    signingConfigs {
        releaseSigning?.let { values ->
            create("release") {
                storeFile = file(values.getValue("keystore"))
                storePassword = values.getValue("storePassword")
                keyAlias = values.getValue("keyAlias")
                keyPassword = values.getValue("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("io.noties.markwon:core:4.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // The unit tests exercise the wire parsing, and `android.jar` only ships an org.json stub.
    testImplementation("org.json:json:20250517")
}

// An unsigned release artifact installs but cannot upgrade an installed one.
tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        check(signingReady) { signingFailure }
    }
}