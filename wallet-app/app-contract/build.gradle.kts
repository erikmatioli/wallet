// The app's API contract: plain @Serializable data classes, no framework. app-api answers with them and
// app-desktop reads them, so a change on one side that the other does not follow fails to compile.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
