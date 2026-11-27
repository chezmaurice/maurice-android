rootProject.name = "maurice-android"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// core: what both apps say to the household server — no UI.
// ui: the look both apps share (theme, reply text).
include(":core")
include(":ui")
include(":maurice:shared", ":maurice:androidApp", ":maurice:desktopApp")
include(":carnet:shared", ":carnet:androidApp", ":carnet:desktopApp")
