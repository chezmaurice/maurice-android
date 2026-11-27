plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
}

// The parity suite runs each module's tests filtered to eu.chezmaurice.parity
// (scripts/parity.sh); a module with none yet is not a failure.
subprojects {
    tasks.withType<Test>().configureEach {
        filter.isFailOnNoMatchingTests = false
        // What the parity tests compare against lives outside the sources: without
        // these, new iOS goldens alone left a module "up to date" and its old
        // verdicts were restored instead of the check being run again.
        listOf("parity/golden", "parity/vectors", "parity/server").forEach {
            inputs.dir(rootProject.file(it)).withPathSensitivity(PathSensitivity.RELATIVE).optional()
        }
    }
}
