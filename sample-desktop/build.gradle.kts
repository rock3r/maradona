import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(25)

    jvm("desktop")

    sourceSets {
        getByName("desktopMain").dependencies {
            implementation(project(":sample-shared"))
            implementation(project(":maradona"))
            implementation(project(":maradona-jewel"))
            implementation(libs.jewel.int.ui.standalone)
            implementation(libs.jewel.markdown.core)
            implementation(libs.jewel.markdown.int.ui.standalone.styling)
            implementation(libs.jewel.markdown.extensions.gfm.tables)
            implementation(libs.jewel.markdown.extensions.autolink)
            implementation(compose.desktop.currentOs)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(project(":sample-shared"))
            implementation(project(":maradona"))
            implementation(project(":maradona-material3"))
            implementation(project(":maradona-jewel"))
            implementation(libs.jewel.int.ui.standalone)
            implementation(libs.jewel.markdown.core)
            implementation(libs.jewel.markdown.int.ui.standalone.styling)
            implementation(libs.jewel.markdown.extensions.gfm.tables)
            implementation(libs.jewel.markdown.extensions.autolink)
        }
    }
}

// The oracle test writes its renders into ../oracle/out/desktop, outside the
// test task's own outputs, and reads the goldens, charts and font. Gradle knew
// about neither, so it was free to skip the task as up to date - and the
// comparison scripts would then happily re-measure the previous run's images and
// report PASS. Declaring the real inputs and outputs fixes the first half; the
// task writing pixels is the whole point, so it always runs.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("oracle/goldens"))
    inputs.dir(rootProject.file("oracle/charts"))
    inputs.dir(rootProject.file("oracle/fonts"))
    inputs.file(rootProject.file("maradona/src/commonMain/kotlin/dev/maradona/layout/NodeSizing.kt"))
    outputs.dir(rootProject.file("oracle/out/desktop"))
    outputs.upToDateWhen { false }
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
