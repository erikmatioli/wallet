import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The customer's desktop app (ADR-001): Compose Multiplatform for Desktop. It only talks to app-api.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":app-contract"))

    // The Compose runtime, UI and Material 3 for the OS this build runs on.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Dispatchers.Main on the desktop is Swing's event thread: the UI only changes there.
    implementation(libs.kotlinx.coroutines.swing)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "br.com.walletapp.desktop.MainKt"
        nativeDistributions {
            // `gradlew :app-desktop:packageMsi` builds a Windows installer with the JVM inside (step 5).
            targetFormats(TargetFormat.Msi)
            packageName = "Wallet"
            packageVersion = "1.0.0"
            // The packaged JVM only gets the modules listed here (on top of Compose's minimal set). Without
            // java.net.http, Ktor's Java engine fails as the window opens - and a packaged app has no console
            // to say so. The list is `gradlew :app-desktop:suggestRuntimeModules`'s answer, plus jdk.localedata:
            // without it the JVM only knows English formats and "R$ 1.234,56" becomes "R$ 1,234.56" - which
            // the suggestion misses, because the locale is only named at runtime.
            modules("java.instrument", "java.management", "java.net.http", "jdk.unsupported", "jdk.localedata")
        }
    }
}
