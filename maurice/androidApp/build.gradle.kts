import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.maurice.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.unifiedpush.connector)
    implementation(libs.ktor.serialization.kotlinx.json)
    // `main` is the Play Store build: the household admin's purchases go through
    // Google Play Billing. The Obtainium/GrapheneOS build is the `foss` branch,
    // which has none of it (Billing.kt is the seam to drop there).
    implementation(libs.play.billing)
}

android {
    namespace = "eu.chezmaurice.maurice"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    // The release key lives outside the repo (~/.maurice-android/release.jks),
    // its password in the macOS Keychain; scripts/build-release.sh hands both
    // over. Without them a release build is simply left unsigned.
    signingConfigs {
        create("release") {
            val keystore = System.getenv("MAURICE_ANDROID_KEYSTORE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("MAURICE_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = "maurice"
                keyPassword = System.getenv("MAURICE_ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "eu.chezmaurice.maurice"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 25
        versionName = "0.5.20"
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            if (System.getenv("MAURICE_ANDROID_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget = JvmTarget.JVM_11 }
}
