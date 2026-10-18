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
include(":maradona", ":sample-desktop", ":sample-shared", ":sample-android")
