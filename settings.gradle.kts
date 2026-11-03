pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LyricStage"

include(":core")

// `-PcoreOnly` builds and tests the pure Kotlin core without the Android SDK.
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
