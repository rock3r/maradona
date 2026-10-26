pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "maradona-root"
include(
    ":maradona",
    ":maradona-material3",
    ":maradona-jewel",
    ":sample-desktop",
    ":sample-shared",
    ":sample-android",
)
