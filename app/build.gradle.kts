import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// set devMode=true in gradle.properties) to compile the developer tests screens
val devMode: Boolean = providers.gradleProperty("devMode").getOrElse("false").toBoolean()

android {
    namespace = "com.foamplayer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.foamplayer.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "alpha"

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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose BOM 2026.08.00+ (Compose 1.12.0) requires compileSdk 37 / AGP 9.1.0 — staying on 2026.06.00 (Compose 1.11.x) to match compileSdk 36 / AGP 9.0.1
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.material:material")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.0")
    implementation("androidx.media3:media3-session:1.9.0")
    implementation("androidx.media3:media3-common:1.9.0")
    implementation("androidx.media3:media3-extractor:1.9.0")

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm) // Correctif Hilt

    // hilt-navigation-compose 1.4.0 requires compileSdk 37 / AGP 9.1.0 — staying on 1.3.0
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation(libs.androidx.palette)

    // Karaoke lyrics
    implementation(libs.lyrics.core)
    implementation(libs.lyrics.ui)

    // Kuromoji
    implementation(libs.kuromoji.ipadic)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")

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

}
