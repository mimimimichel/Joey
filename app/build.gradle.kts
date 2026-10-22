plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Clé Gemini intégrée à l'APK (facultatif) : propriété -PgeminiKey=... ou variable GEMINI_API_KEY.
// Jamais dans le code : elle n'est lue qu'au moment de construire l'APK. Sinon : réglages de l'app (⚙).
val geminiKey: String = (project.findProperty("geminiKey") as String?)?.takeIf { it.isNotBlank() }
    ?: System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
    ?: ""

android {
    namespace = "com.joey.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.joey.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.14"
        buildConfigField("String", "GEMINI_KEY", "\"" + geminiKey + "\"")
        // Seulement les processeurs ARM 64 bits (S10, S8+ et presque tous depuis 2017) : APK bien plus léger.
        // -PallAbis=true pour tout inclure.
        if (project.findProperty("allAbis") != "true") ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }

    // Même signature pour toutes les versions (sinon Android refuse la mise à jour et la clé Gemini est perdue).
    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    androidResources {
        noCompress += "tflite"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric télécharge Android lui-même : par le miroir (Maven Central limite parfois)
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2") }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // WebSocket pour la conversation en direct avec Gemini Live
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Caméra avant : les yeux de Joey (images envoyées à Gemini)
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // Détection des visages sur le téléphone (sans Internet, modèle inclus) : la tête suit la personne
    implementation("com.google.mlkit:face-detection:16.1.7")
    // Détecteur d'objets sur le téléphone (MediaPipe, EfficientDet-Lite2, 80 catégories COCO) : « cherche le chien »
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
    // Reconnaissance des visages de la maison (MobileFaceNet, sur le téléphone : empreinte de 192 nombres par visage)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // Carte USB-série (FTDI, CH340, CP2102) pour piloter le Roomba
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}
