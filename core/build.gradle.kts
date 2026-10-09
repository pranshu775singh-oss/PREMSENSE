import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: no Android types are reachable from here, so every rule, statistic and
// ledger operation can be unit tested and replayed offline without a phone or emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation(libs.junit)

    // Reference implementations used only as test oracles for the hand-written statistics.
    testImplementation(libs.commons.math3)

    // The independent verifier checks the exports the exporter writes (see the mutation tests).
    testImplementation(project(":verifier"))
}
