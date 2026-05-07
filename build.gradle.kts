import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    id("io.canvasmc.weaver.patcher") version "2.4.4-SNAPSHOT"
    id("xyz.jpenilla.resource-factory-paper-convention") version "1.3.1" apply false
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

paperweight {
    filterPatches = false
    gitFilePatches = false

    upstreams.canvas {
        ref = providers.gradleProperty("canvasCommit")

        patchFile {
            path = "canvas-server/build.gradle.kts"
            outputFile = file("surf-canvas-server/build.gradle.kts")
            patchFile = file("surf-canvas-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "canvas-api/build.gradle.kts"
            outputFile = file("surf-canvas-api/build.gradle.kts")
            patchFile = file("surf-canvas-api/build.gradle.kts.patch")
        }
        patchDir("canvasApi") {
            upstreamPath = "canvas-api"
            patchesDir = file("surf-canvas-api/canvas-patches")
            outputDir = file("canvas-api")
        }
        patchDir("canvasServer") {
            upstreamPath = "canvas-server"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch")
            patchesDir = file("surf-canvas-server/canvas-patches")
            outputDir = file("canvas-server")
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven("https://maven.canvasmc.io/public")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }

    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://reposilite.slne.dev/releases/") {
                name = "slne-repository-releases"
                credentials {
                    username = providers.environmentVariable("SLNE_RELEASES_REPO_USERNAME").orNull
                    password = providers.environmentVariable("SLNE_RELEASES_REPO_PASSWORD").orNull
                }
            }
        }
    }

    if (project.name.endsWith("-debug") || project.name.endsWith("-plugin")) {
        apply(plugin = "xyz.jpenilla.resource-factory-paper-convention")
        dependencies {
            compileOnly(rootProject.projects.surfCanvasServer) {
                targetConfiguration = JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME
            }
        }
        extensions.configure<xyz.jpenilla.resourcefactory.paper.PaperPluginYaml> {
            apiVersion.set(providers.gradleProperty("apiVersion"))
            version = "SNAPSHOT-DEV"
            main = project.findProperty("main")?.toString()?.replace("\"", "")
            authors = listOf("twisti", "SLNE-Development")
            foliaSupported = true
            if (project.hasProperty("bootstrapper")) {
                bootstrapper = project.findProperty("bootstrapper")?.toString()?.replace("\"", "")
            }
            tasks.processResources {
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
        }
    }
}

tasks.register("fixupMinecraftFilePatches") {
    dependsOn(":surf-canvas-server:fixupMinecraftSourcePatches")
}