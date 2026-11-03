import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import javax.inject.Inject

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.8.2"
}

group = "am.applicationmanager"
version = file("VERSION").readText().trim()

kotlin { jvmToolchain(17) }

val installerName = "Application-Manager"

compose.desktop {
    application {
        mainClass = "am.applicationmanager.MainKt"
        if (System.getProperty("os.name").equals("Linux", ignoreCase = true)) {
            jvmArgs("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
        }
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm)
            packageName = installerName
            packageVersion = project.version.toString()
            description = "Linux application manager with Compose Desktop"
            linux {
                packageName = "application-manager"
                iconFile.set(project.file("src/main/resources/app-icon.png"))
                shortcut = true
                menuGroup = "Utility"
            }
        }
    }
}

// Compose's native packaging tools do not inherit Kotlin's JDK toolchain.
val packagingJavaHome = javaToolchains.compilerFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}.map { it.metadata.installationPath.asFile.absolutePath }

// Compose assigns its default Java home after evaluation; override it afterwards.
afterEvaluate {
    tasks.withType<AbstractCheckNativeDistributionRuntime>().configureEach {
        jdkHome.set(packagingJavaHome)
    }
    tasks.withType<AbstractJvmToolOperationTask>().configureEach {
        javaHome.set(packagingJavaHome)
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.github.weisj:jsvg:2.2.0")
}

tasks.processResources {
    from(layout.projectDirectory.file("VERSION"))
}

interface PackagingServices {
    @get:Inject
    val execOperations: ExecOperations
}

val packagingServices = objects.newInstance<PackagingServices>()
val linuxPackagingDir = layout.projectDirectory.dir("packaging/linux")
val fixLinuxDebScript = layout.projectDirectory.file("tools/fix-linux-deb.sh")
val appIcon = layout.projectDirectory.file("src/main/resources/app-icon.png")
val appVersion = project.version.toString()
val linuxIconsDirectory = layout.buildDirectory.dir("linux-icons")
val prepareLinuxIconsScript = layout.projectDirectory.file("tools/PrepareLinuxIcons.java")

val prepareLinuxIcons = tasks.register("prepareLinuxIcons") {
    inputs.file(appIcon)
    inputs.file(prepareLinuxIconsScript)
    outputs.dir(linuxIconsDirectory)
    doLast {
        packagingServices.execOperations.exec {
            commandLine(
                file("${packagingJavaHome.get()}/bin/java").absolutePath,
                "-Djava.awt.headless=true", prepareLinuxIconsScript.asFile.absolutePath,
                appIcon.asFile.absolutePath, linuxIconsDirectory.get().asFile.absolutePath,
                "am.applicationmanager.ApplicationManager",
            )
        }.assertNormalExitValue()
    }
}

tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Deb) {
        dependsOn(prepareLinuxIcons)
        inputs.dir(linuxPackagingDir)
        inputs.file(fixLinuxDebScript)
        inputs.dir(linuxIconsDirectory)
        doLast {
            val deb = destinationDir.get().asFile.listFiles()
                .orEmpty().single { it.extension == "deb" }
            packagingServices.execOperations.exec {
                commandLine(
                    "bash", fixLinuxDebScript.asFile.absolutePath,
                    deb.absolutePath, appIcon.asFile.absolutePath,
                    linuxPackagingDir.asFile.absolutePath, appVersion,
                    linuxIconsDirectory.get().asFile.absolutePath,
                )
            }.assertNormalExitValue()
        }
    }
}

tasks.register<Copy>("buildRelease") {
    group = "distribution"
    description = "Build a local DEB in build/release/[name]-[version]-[architecture].deb"

    val debPackage = tasks.named<AbstractJPackageTask>("packageDeb")
    val releaseVersion = project.version.toString()
    dependsOn(debPackage)
    from(debPackage.flatMap { it.destinationDir }) {
        include("*.deb")
        rename { filename ->
            // jpackage names DEBs <name>_<version>_<Debian architecture>.deb.
            val parts = filename.removeSuffix(".deb").split('_')
            require(parts.size == 3) { "Unexpected DEB filename: $filename" }
            "$installerName-$releaseVersion-${parts[2]}.deb"
        }
    }
    into(layout.buildDirectory.dir("release"))
}
