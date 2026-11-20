plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNummer = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.wuse123mann.einkaufszettel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wuse123mann.einkaufszettel"
        minSdk = 28
        targetSdk = 35
        versionCode = buildNummer
        versionName = "1.0.$buildNummer"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Screenshots aus dem Test werden über den Test-Storage-Dienst eingesammelt
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        // nur Handy-Prozessoren: halbiert die APK-Größe (Offline-Texterkennung)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            // Eigener Schlüssel im (privaten) Repo, damit jede neue APK
            // als Update über die alte installiert werden kann.
            storeFile = file("../keystore/einkaufszettel.jks")
            storePassword = "einkauf2026"
            keyAlias = "einkaufszettel"
            keyPassword = "einkauf2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Offline-Texterkennung (läuft komplett am Handy, kein Konto nötig)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Screenshot-Test (läuft im Emulator auf GitHub, siehe .github/workflows/screenshots.yml)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.services:storage:1.5.0")
    androidTestUtil("androidx.test.services:test-services:1.5.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    // Netzwerk-Stack von Chrome (QUIC/HTTP3) über die Google-Play-Dienste
    implementation("com.google.android.gms:play-services-cronet:18.1.0")
}
