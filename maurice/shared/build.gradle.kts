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
        namespace = "eu.chezmaurice.maurice.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core)
            api(projects.ui)
            implementation(libs.navigation.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.compose.components.resources)
            implementation(libs.markdown.renderer.m3)
            implementation(libs.markdown.renderer.coil3)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // Invitation QR codes: the camera (AOSP's own, no Play Services) and
            // ZXing (plain Java) to read them.
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
            implementation(libs.zxing.core)
            implementation(libs.unifiedpush.connector)
        }
        jvmMain.dependencies {
            // Invitation QR codes on the desktop: ZXing is plain Java.
            implementation(libs.zxing.core)
        }
        // The parity suite against the iOS app (parity/README.md).
        jvmTest {
            kotlin.srcDir(rootProject.file("parity/android/harness"))
            kotlin.srcDir(rootProject.file("parity/android/maurice"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    packageOfResClass = "eu.chezmaurice.maurice.resources"
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}
