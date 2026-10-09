plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ojas.permsense"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ojas.permsense"

        minSdk = 29
        targetSdk = 37

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    implementation(
        libs.androidx.activity.compose
    )

    implementation(
        libs.androidx.compose.material3
    )

    implementation(libs.androidx.compose.material.icons.core)

    implementation(
        libs.androidx.compose.ui
    )

    implementation(
        libs.androidx.compose.ui.graphics
    )

    implementation(
        libs.androidx.compose.ui.tooling.preview
    )

    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.androidx.lifecycle.runtime.ktx
    )

    testImplementation(
        libs.junit
    )
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Reads AppOps in the app's own process once the one-time grant has been made. Isolated
    // behind HiddenApi.kt so that a future Android release breaking it is a one-file fix.
    implementation(libs.hiddenapibypass)

    // Scheduled work: the 15-minute snapshot poll, the nightly retention pass, the weekly digest.
    implementation(libs.androidx.work.runtime.ktx)

    // App lock with the phone's own biometric or PIN prompt.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    androidTestImplementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}