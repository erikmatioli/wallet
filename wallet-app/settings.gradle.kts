rootProject.name = "wallet-app"

// ADR-001: one build, three modules. app-contract is shared by the other two.
include("app-contract", "app-api", "app-desktop")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google() // Compose Multiplatform's Android-flavoured artifacts are resolved from here, even on desktop
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
