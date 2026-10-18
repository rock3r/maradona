plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
}

kotlin {
    // All library code lives in commonMain and uses only Compose Multiplatform
    // common APIs, so every target shares the same sources.
    jvm("desktop")
    android {
        namespace = "dev.maradona"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        // androidx.tracing is KMP but publishes only android + desktop (JVM)
        // variants, so it can only go in those two source sets.
        androidMain.dependencies {
            implementation(libs.androidx.tracing)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.androidx.tracing)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
