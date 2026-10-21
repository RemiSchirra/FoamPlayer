pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FoamPlayer"
include(":app")
include(":spotify")
include(":discord")
include(":lastfm")
include(":canvas:core")
include(":canvas:apple")
include(":canvas:echomusic")
include(":canvas:tidal")
include(":canvas:vivimusic")
include(":lyrics:betterlyrics")
include(":lyrics:lrclib")
include(":lyrics:kugou")
include(":lyrics:paxsenix")
include(":lyrics:simpmusic")
include(":lyrics:unison")
include(":lyrics:youlyplus")
include(":distant:core")
include(":distant:plex")
include(":distant:jellyfin")
