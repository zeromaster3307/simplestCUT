import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release-Schlüssel liegt bewusst außerhalb des Repos (siehe scripts/release.sh).
val releaseKeyDir = rootProject.file("../simplestCUT-schluessel")
val releaseKeyProps = Properties().apply {
    val f = File(releaseKeyDir, "release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.simplestcut"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.simplestcut"
        minSdk = 35
        targetSdk = 36
        versionCode = 16
        versionName = "0.8.9"
    }

    signingConfigs {
        if (releaseKeyProps.isNotEmpty()) {
            create("release") {
                storeFile = File(releaseKeyDir, releaseKeyProps.getProperty("storeFile"))
                storePassword = releaseKeyProps.getProperty("storePassword")
                keyAlias = releaseKeyProps.getProperty("keyAlias")
                keyPassword = releaseKeyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Eigenes Paket für die Entwicklungsversion, damit sie neben der Play-Store-Version installiert sein kann
        // (Name unter dem Symbol: "simplestCUT Dev", siehe src/debug/res).
        debug {
            applicationIdSuffix = ".dev"
        }
        // R8: entfernt ungenutzten Code und verkürzt Namen (Google verlangt mind. 25 % "Verschleierung").
        // Die Zuordnungsdatei für Absturzberichte kommt automatisch ins App-Bundle.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Wie release (mit R8), aber als Dev-Paket mit Debug-Schlüssel und Testwerkzeugen: zum Prüfen, ob R8 nichts
        // Nötiges entfernt hat (./gradlew assembleMinified).
        create("minified") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    sourceSets {
        getByName("minified") {
            java.srcDir("src/debug/java")
            kotlin.srcDir("src/debug/java")
            res.srcDir("src/debug/res")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }

    buildFeatures {
        compose = true
    }

    // Sprache pro App in den Android-Einstellungen (Englisch = Grundsprache, siehe res/resources.properties).
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    debugImplementation(libs.compose.ui.tooling)
}
