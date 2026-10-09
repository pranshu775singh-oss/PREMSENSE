import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The offline verifier for PermSense evidence reports.
//
// It deliberately shares no code with the app or with :core: it has its own JSON reader, its own
// hashing and its own signature checks. A recipient (a caseworker, a regulator, a journalist)
// should not have to trust PermSense's own code to check a report that PermSense produced.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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

application {
    mainClass.set("permsense.verifier.MainKt")
    applicationName = "permsense-verify"
}

dependencies {
    testImplementation(libs.junit)
}
