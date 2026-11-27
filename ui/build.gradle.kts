import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // Java 21: the desktop libraries (the Markdown renderer among them) are
    // compiled for it. Android code still targets JVM 11 below.
    jvmToolchain(21)
    jvm()

    androidLibrary {
        namespace = "eu.chezmaurice.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.ui)
            api(libs.compose.components.resources)
            api(libs.compose.material.icons.core)
            implementation(libs.compose.uiToolingPreview)
            api(libs.coil.compose)
            api(libs.coil.network.ktor)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "eu.chezmaurice.ui.generated.resources"
}
