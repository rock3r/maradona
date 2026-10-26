plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.maven.publish)
}

kotlin {
    // Jewel is a Desktop JVM look-and-feel library; keeping this connector
    // desktop-only ensures Android and iOS targets never pull in Jewel dependencies.
    jvm("desktop")

    sourceSets {
        getByName("desktopMain").dependencies {
            api(project(":maradona"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            api(libs.jewel.foundation)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.currentOs)
            implementation(libs.jewel.int.ui.standalone)
        }
    }
}
