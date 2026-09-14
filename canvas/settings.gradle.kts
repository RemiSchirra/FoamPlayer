rootProject.name = "canvas"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

include(":core")
include(":apple")
include(":echomusic")
include(":tidal")
include(":vivimusic")