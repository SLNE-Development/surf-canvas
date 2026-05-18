import java.util.Locale

pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven {
            name = "canvasmc"
            url = uri("https://maven.canvasmc.io/public")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    val errorText = """
        =====================[ ERROR ]=====================
         The surf-canvas project directory is not a properly cloned Git repository.

         In order to build Surf from source you must clone
         the surf-canvas repository using Git, not download a code
         zip from GitHub.
        ===================================================
    """.trimIndent()
    error(errorText)
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "surf-canvas"

for (name in listOf("surf-canvas-api", "surf-canvas-server")) {
    val projName = name.lowercase(Locale.ENGLISH)
    include(projName)
    findProject(":$projName")!!.projectDir = file(name)
}

optionalInclude("test-plugin")

fun optionalInclude(name: String, op: (ProjectDescriptor.() -> Unit)? = null) {
    val settingsFile = file("$name.settings.gradle.kts")
    if (settingsFile.exists()) {
        apply(from = settingsFile)
        findProject(":$name")?.let { op?.invoke(it) }
    } else {
        settingsFile.writeText(
            """
            // Uncomment to enable the '$name' project
            // include(":$name")

            """.trimIndent()
        )
    }
}

//rootDir.listFiles()
//    ?.filter { it.isDirectory && (it.name.endsWith("-debug", ignoreCase = true) || it.name.endsWith("-plugin", ignoreCase = true)) }
//    ?.forEach { dir ->
//        val projName = dir.name.lowercase(Locale.ENGLISH)
//        include(projName)
//        findProject(":$projName")!!.projectDir = dir
//    }

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val surfChannel = providers.gradleProperty("channel").get().trim()
    val surfBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (surfBuildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$surfBuildNumber-${surfChannel.lowercase()}"
    }
    version = versionString
}
