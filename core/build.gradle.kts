import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    // Java 21: the desktop libraries (the Markdown renderer among them) are
    // compiled for it. Android code still targets JVM 11 below.
    jvmToolchain(21)
    jvm()

    androidLibrary {
        namespace = "eu.chezmaurice.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
        withHostTest {}
        // On a phone or the emulator: `./gradlew :core:connectedAndroidDeviceTest`.
        withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        // What Android and the desktop share as JVMs: java.security, BigInteger.
        val jvmShared by creating { dependsOn(commonMain.get()) }
        androidMain {
            dependsOn(jvmShared)
            dependencies { implementation(libs.ktor.client.okhttp) }
        }
        jvmMain {
            dependsOn(jvmShared)
            dependencies { implementation(libs.ktor.client.okhttp) }
        }
        // The parity suite against the iOS apps (parity/README.md).
        jvmTest {
            kotlin.srcDir(rootProject.file("parity/android/harness"))
            kotlin.srcDir(rootProject.file("parity/android/core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
            implementation(kotlin("test-junit"))
        }
    }
}
