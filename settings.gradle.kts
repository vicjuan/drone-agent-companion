pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "drone-agent-companion"

// JDK-only modules are always available, including on CI hosts without an
// Android SDK. web-console is an npm project rather than a Gradle subproject.
include(":console-protocol")
include(":capability-matrix")
include(":commissioning-network")
include(":console-server")
include(":console-adapter-mock")
include(":console-runner")
include(":vision-opencv-core")
include(":vision-opencv-desktop")

fun File.hasSdkDirProperty(): Boolean =
    isFile && readLines().any { line ->
        line.trimStart().startsWith("sdk.dir=") && line.substringAfter('=').isNotBlank()
    }

val sharedAndroidSdkEnvironment =
    !System.getenv("ANDROID_HOME").isNullOrBlank() ||
        !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank()
val companionHasLocalSdk = file("local.properties").hasSdkDirProperty()
val vendorHasLocalSdk = file("vendor/drone-agent-android/local.properties").hasSdkDirProperty()

// An included build configures independently. A local.properties file in only
// one build is therefore insufficient; environment variables are the preferred
// way to expose one SDK consistently to both builds.
val androidSdkAvailable =
    sharedAndroidSdkEnvironment || (companionHasLocalSdk && vendorHasLocalSdk)

if (androidSdkAvailable) {
    include(":vision-opencv-android")
    include(":host-headless")
} else {
    gradle.rootProject {
        logger.warn(
            "[drone-agent-companion] Android SDK not shared with both builds. " +
                "Android modules (:vision-opencv-android, :host-headless) are EXCLUDED; " +
                "building the pure-JVM workspace only.",
        )
    }
}

includeBuild("vendor/drone-agent-android") {
    dependencySubstitution {
        // The vendor build intentionally does not publish group/version metadata.
        // Stable local coordinates make every cross-build dependency explicit.
        substitute(module("com.durendal.droneagent:core")).using(project(":core"))
        substitute(module("com.durendal.droneagent:drone-actuation")).using(project(":drone-actuation"))
        substitute(module("com.durendal.droneagent:gateway")).using(project(":gateway"))
        substitute(module("com.durendal.droneagent:vision")).using(project(":vision"))
        substitute(module("com.durendal.droneagent:adapter-mock")).using(project(":adapter-mock"))

        if (androidSdkAvailable) {
            substitute(module("com.durendal.droneagent:drone-observation")).using(project(":drone-observation"))
            substitute(module("com.durendal.droneagent:adapter-dji")).using(project(":adapter-dji"))
        }
    }
}
