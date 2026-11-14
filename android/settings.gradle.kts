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

rootProject.name = "PocketLog"
include(":app")

// Explicit synthetic capture fixture only; never part of normal APK/CI tasks.
if (providers.gradleProperty("captureFixture").orNull == "true") include(":capture-fixture")
