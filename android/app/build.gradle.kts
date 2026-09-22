import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val releaseSigning = mapOf(
    "keystore" to providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull,
    "storePassword" to providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull,
    "keyAlias" to providers.environmentVariable("ANDROID_KEY_ALIAS").orNull,
    "keyPassword" to providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull,
)
require(
    releaseSigning.values.all { it == null } ||
        releaseSigning.values.all { !it.isNullOrBlank() },
) {
    "Set every Android release signing environment variable or none of them"
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
        versionCode = 1001
        versionName = "0.1.1"
    }
    signingConfigs {
        if (releaseSigning.values.all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(releaseSigning.getValue("keystore")!!)
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
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
