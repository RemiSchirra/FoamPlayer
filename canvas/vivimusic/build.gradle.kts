plugins {
    alias(libs.plugins.kotlin.serialization)
    id("java-library")
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(findProject(":canvas:core")?.path ?: ":core"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.encoding)
    testImplementation(libs.junit)
}