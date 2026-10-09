// QuickCash: the probe app (spec block 6, acceptance S0).
//
// Not shipped to users. It is ground truth: an app the team controls completely, that performs
// real, known accesses on a deterministic schedule and writes a signed log of what it MEANT to
// do, so "what PermSense should have seen" is provable rather than asserted.
//
// Two builds of the same app, v1 and v2, share one package name and one signing key, so v2 installs
// over v1 with `adb install -r`, exactly like an update from a store. v2 behaves differently: that
// is the update-drift demonstration.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ojas.probe"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ojas.quickcash"

        minSdk = 29
        targetSdk = 37
    }

    flavorDimensions += "version"

    productFlavors {
        create("v1") {
            dimension = "version"
            versionCode = 1
            versionName = "1.0-test"
            buildConfigField("int", "PROBE_GENERATION", "1")
        }
        create("v2") {
            dimension = "version"
            versionCode = 2
            versionName = "2.0-test"
            buildConfigField("int", "PROBE_GENERATION", "2")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
