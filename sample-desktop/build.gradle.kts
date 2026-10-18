import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm("desktop")

    sourceSets {
        getByName("desktopMain").dependencies {
            implementation(project(":sample-shared"))
            implementation(compose.desktop.currentOs)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(project(":maradona"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.maradona.sample.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "maradona-sample"
            packageVersion = "0.1.0"
        }
    }
}
