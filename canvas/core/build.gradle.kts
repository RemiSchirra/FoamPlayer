plugins {
    alias(libs.plugins.kotlin.serialization)
    id("java-library")
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
