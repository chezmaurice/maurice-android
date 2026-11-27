import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    // Java 21: the desktop libraries (the Markdown renderer among them) are
    // compiled for it. Android code still targets JVM 11 below.
    jvmToolchain(21)
    jvm()

    androidLibrary {
        namespace = "eu.chezmaurice.carnet.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core)
            api(projects.ui)
            // The screens both apps share: pairing, the member picker and its
            // PIN and invitations, Markdown replies, the platform pieces.
            api(projects.maurice.shared)
            implementation(libs.navigation.compose)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        // The parity suite against the iOS app (parity/README.md).
        jvmTest {
            kotlin.srcDir(rootProject.file("parity/android/harness"))
            kotlin.srcDir(rootProject.file("parity/android/carnet"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.currentOs)
        }
    }
}

// Carnet's words (files/strings, synced from the iOS app; files/android, its own).
compose.resources {
    packageOfResClass = "eu.chezmaurice.carnet.resources"
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}
