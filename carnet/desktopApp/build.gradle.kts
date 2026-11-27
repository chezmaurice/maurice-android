import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(projects.carnet.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        // The runtime bundled in the installers, and the one `run` uses.
        javaHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
            .get().metadata.installationPath.asFile.absolutePath
        mainClass = "eu.chezmaurice.carnet.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Carnet"
            // Following a moved household: DNS TXT through JNDI, Ed25519 (core's HouseholdMove).
            modules("java.naming", "jdk.naming.dns", "jdk.crypto.ec")
            windows { iconFile.set(project.file("icons/carnet.ico")) }
            linux { iconFile.set(project.file("icons/carnet.png")) }
            packageVersion = "0.1.0"
            vendor = "Maurice"
        }
    }
}
