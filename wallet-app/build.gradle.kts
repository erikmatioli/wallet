// Plugins are declared here once, with their versions from gradle/libs.versions.toml, and applied
// by each module that needs them - so the whole build agrees on one Kotlin and one Spring Boot.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "br.com.walletapp"
    version = "0.1.0-SNAPSHOT"
}
