import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.impl.VariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// set devMode=true in gradle.properties to compile the developer tests screens
val devMode: Boolean = providers.gradleProperty("devMode").getOrElse("false").toBoolean()

android {
    namespace = "com.foamplayer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.foamplayer.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("boolean", "DEV_MODE", devMode.toString())
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

androidComponents {
    onVariants { variant ->
        if (variant.buildType == "release") {
            variant.outputs.forEach { output ->
                val abi = output.filters
                    .find { it.filterType == FilterConfiguration.FilterType.ABI }
                    ?.identifier ?: "universal"

                (output as VariantOutputImpl).outputFileName.set(
                    output.versionName.map { "foamplayer-$it-$abi.apk" }
                )
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.jaudiotagger)

    // Compose BOM 2026.08.00+ (Compose 1.12.0) requires compileSdk 37 / AGP 9.1.0 — staying on 2026.06.00 (Compose 1.11.x) to match compileSdk 36 / AGP 9.0.1
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.extractor)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm) // Correctif Hilt

    // hilt-navigation-compose 1.4.0 requires compileSdk 37 / AGP 9.1.0 — staying on 1.3.0
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)

    // Karaoke lyrics
    implementation(libs.lyrics.core)
    implementation(libs.lyrics.ui)

    // Kuromoji
    implementation(libs.kuromoji.ipadic)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)

    implementation(project(":spotify"))
    implementation(project(":discord"))
    implementation(project(":canvas:apple"))
    implementation(project(":canvas:echomusic"))
    implementation(project(":canvas:tidal"))
    implementation(project(":canvas:vivimusic"))
    implementation(project(":lyrics:betterlyrics"))
    implementation(project(":lyrics:kugou"))
    implementation(project(":lyrics:lrclib"))
    implementation(project(":lyrics:paxsenix"))
    implementation(project(":lyrics:simpmusic"))
    implementation(project(":lyrics:unison"))
    implementation(project(":lyrics:youlyplus"))

    implementation(project(":distant:core"))
    implementation(project(":distant:plex"))
    implementation(project(":distant:jellyfin"))

}