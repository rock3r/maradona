plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(25)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

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
            api(libs.jewel.markdown.core)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.currentOs)
            implementation(libs.jewel.int.ui.standalone)
            implementation(libs.jewel.markdown.int.ui.standalone.styling)
            implementation(libs.jewel.markdown.extensions.gfm.tables)
            implementation(libs.jewel.markdown.extensions.autolink)
        }
    }
}
