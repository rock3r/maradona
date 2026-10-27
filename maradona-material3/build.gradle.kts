plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(25)

    jvm("desktop")
    android {
        namespace = "dev.maradona.theme.material3"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":maradona"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            api(libs.compose.material3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("desktopTest").dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
