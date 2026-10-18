plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
}

kotlin {
    jvm("desktop")
    android {
        namespace = "dev.maradona.sample.shared"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "MaradonaSample"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":maradona"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
    }
}
