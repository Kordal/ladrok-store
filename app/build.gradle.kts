import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: the same key as Ladrok Launcher. It also signs the Catalog: the Store trusts a
// Catalog only if it is signed with the key the Store itself is signed with (see Catalog.kt).
// In CI it comes from the repo's secrets (see .github/workflows/release.yml);
// on your computer from ~/.config/ladrok/launcher-signing.properties (or LADROK_SIGNING=<file>).
val signing = Properties().apply {
    val file = System.getenv("LADROK_SIGNING")?.let(::file)
        ?: file("${System.getProperty("user.home")}/.config/ladrok/launcher-signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
    System.getenv("LADROK_KEYSTORE")?.let { setProperty("storeFile", it) }
    System.getenv("LADROK_KEYSTORE_PASSWORD")?.let { setProperty("storePassword", it); setProperty("keyPassword", it) }
}
// CI sets these from the tag (v1.2.0) and the run number, so every release is an update.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val version = System.getenv("LADROK_VERSION") ?: "0.0.0-dev"

android {
    namespace = "tv.ladrok.store"
    compileSdk = 37

    defaultConfig {
        applicationId = "tv.ladrok.store"
        minSdk = 31 // Android 12: runs on an emulator or a stock Android TV box too, not only Ladrok TV
        targetSdk = 37 // AOSP 17
        versionCode = buildNumber
        versionName = version
        // Published by the resolver (.github/workflows/catalog.yml) on the rolling "catalog" release.
        buildConfigField("String", "CATALOG_URL", "\"https://github.com/Kordal/ladrok-store/releases/download/catalog/catalog.json\"")
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias") ?: "ladrok-launcher"
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.coil.compose) // App icons from the Catalog
    implementation(libs.coil.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
