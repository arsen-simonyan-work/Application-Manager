plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.8.2"
}

group = "am.applicationmanager"
version = file("VERSION").readText().trim()

kotlin { jvmToolchain(17) }

compose.desktop {
    application {
        mainClass = "am.applicationmanager.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm)
            packageName = "application-manager"
            packageVersion = project.version.toString()
            description = "Linux application manager with Compose Desktop"
            linux { iconFile.set(project.file("src/main/resources/app-icon.png")) }
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.github.weisj:jsvg:2.2.0")
}
