pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Versions declared here are only resolved by modules that apply the plugin, so the
    // DNA core can be built without access to Google's Maven repository / the Android SDK.
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.1.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
        id("org.jetbrains.kotlin.jvm") version "2.1.0"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DNAsq"

include(":dnacore")
// The Android module needs the Android SDK. Set -PcoreOnly=true to build/test
// only the platform-independent DNA core (e.g. on machines without the SDK).
if (providers.gradleProperty("coreOnly").orNull != "true") {
    include(":app")
}
