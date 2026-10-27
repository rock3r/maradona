plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
}

kotlin {
    jvmToolchain(25)

    jvm("desktop")
    android {
        namespace = "dev.maradona.sample.shared"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
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
            api(project(":maradona"))
            api(project(":maradona-material3"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
    }
}
