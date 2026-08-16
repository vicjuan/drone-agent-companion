import java.io.File
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val candidateCommit =
    providers.exec {
        workingDir(rootProject.layout.projectDirectory)
        commandLine("git", "rev-parse", "--verify", "HEAD^{commit}")
    }.standardOutput.asText.map(String::trim)
val candidateWorktreeState =
    providers.exec {
        workingDir(rootProject.layout.projectDirectory)
        commandLine(
            "git",
            "status",
            "--porcelain=v1",
            "--untracked-files=all",
            "--ignore-submodules=none",
        )
    }.standardOutput.asText.map { status ->
        if (status.isBlank()) "clean" else "dirty"
    }
val generatedCandidateAssets = layout.buildDirectory.dir("generated/candidateAssets")
val generatedCandidateIdentityDirectory =
    generatedCandidateAssets.map { it.dir("companion-candidate") }
val djiApiKey = providers.gradleProperty("djiApiKey").orElse("")
val djiApplicationId =
    providers.gradleProperty("djiApplicationId")
        .orElse("com.durendal.droneagent.companion.host")
val dji518Arm64LibcxxSha256 =
    "b17919df195f29b0a238468c04171b93cedcdffbe0d30cdc634327cf7f7889bc"

fun gitOutput(vararg arguments: String): String {
    val process =
        ProcessBuilder(
            listOf("git", "-C", rootProject.layout.projectDirectory.asFile.absolutePath) +
                arguments,
        ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) {
        "git ${arguments.joinToString(" ")} failed: $output"
    }
    return output
}

fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

android {
    namespace = "com.durendal.droneagent.companion.host"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.durendal.droneagent.companion.host"
        // The embedded Ktor/static-file path uses java.nio APIs that Android's
        // desugaring lane does not provide on API 24/25.
        minSdk = 26
        targetSdk = 34
        multiDexEnabled = true
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    flavorDimensions += "adapter"
    productFlavors {
        create("dji") {
            dimension = "adapter"
            // DJI Developer keys are bound to the Android application ID. Keep the new companion
            // package as the safe default, but allow an operator to build against an existing
            // registered DJI package only through an explicit, non-committed Gradle property.
            applicationId = djiApplicationId.get()
            manifestPlaceholders["djiApiKey"] = djiApiKey.get()
        }
        create("mock") {
            dimension = "adapter"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/companionWebAssets"))
        getByName("main").assets.srcDir(generatedCandidateAssets)
        // The emulator lifecycle harness must prove that its helper APK was built from the
        // same frozen candidate as the target APK before it clears Android's stopped state.
        getByName("androidTest").assets.srcDir(generatedCandidateAssets)
    }

    packaging {
        resources.excludes +=
            setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        // DJI aircraft/wpmz SDKs and the official OpenCV Android AAR each package the same
        // C++ runtime. Match DJI's release app packaging so arm64 extraction and merge are
        // deterministic on G520 rather than failing duplicate-native-library assembly.
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("lib/arm64-v8a/libc++_shared.so")
        }
    }
}

val packageCompanionWebAssets by tasks.registering(Sync::class) {
    dependsOn(":console-runner:buildWebConsole")
    from(rootProject.layout.projectDirectory.dir("web-console/dist")) {
        into("companion-web")
    }
    into(layout.buildDirectory.dir("generated/companionWebAssets"))
}

val generateCandidateIdentity by tasks.registering {
    val commitOutput = generatedCandidateIdentityDirectory.map { it.file("commit.txt") }
    val worktreeOutput = generatedCandidateIdentityDirectory.map { it.file("worktree-state.txt") }
    inputs.property("candidateCommit", candidateCommit)
    inputs.property("candidateWorktreeState", candidateWorktreeState)
    outputs.dir(generatedCandidateAssets)
    doLast {
        val commit = candidateCommit.get()
        val worktreeState = candidateWorktreeState.get()
        require(Regex("[0-9a-fA-F]{40,64}").matches(commit)) {
            "HEAD did not resolve to a bounded Git commit"
        }
        require(worktreeState == "clean" || worktreeState == "dirty") {
            "worktree state must be clean or dirty"
        }
        val candidateAssetsRoot = generatedCandidateAssets.get().asFile
        delete(candidateAssetsRoot)
        require(candidateAssetsRoot.mkdirs()) { "cannot create candidate assets root" }
        val identityDirectory = generatedCandidateIdentityDirectory.get().asFile
        require(identityDirectory.mkdirs()) { "cannot create candidate identity directory" }
        commitOutput.get().asFile.apply {
            writeText("$commit\n", Charsets.US_ASCII)
        }
        worktreeOutput.get().asFile.apply {
            writeText("$worktreeState\n", Charsets.US_ASCII)
        }
        require(identityDirectory.listFiles().orEmpty().map { it.name }.toSet() ==
            setOf("commit.txt", "worktree-state.txt")) {
            "candidate identity directory differs from its exact allowlist"
        }
        require(candidateAssetsRoot.listFiles().orEmpty().map { it.name }.toSet() ==
            setOf("companion-candidate")) {
            "candidate assets root differs from its exact allowlist"
        }
    }
}

val verifyDjiArm64NativeRuntime by tasks.registering {
    group = "verification"
    description = "Proves the merged arm64 C++ runtime is the DJI 5.18.0 copy, not OpenCV's older copy."
    dependsOn("mergeDjiDebugNativeLibs")
    val mergedRuntime =
        layout.buildDirectory.file(
            "intermediates/merged_native_libs/djiDebug/mergeDjiDebugNativeLibs/" +
                "out/lib/arm64-v8a/libc++_shared.so",
        )
    inputs.file(mergedRuntime)
    doLast {
        val runtime = mergedRuntime.get().asFile
        require(runtime.isFile) { "merged DJI arm64 libc++_shared.so is missing" }
        require(runtime.sha256Hex() == dji518Arm64LibcxxSha256) {
            "merged arm64 libc++_shared.so is not the frozen DJI 5.18.0 runtime"
        }
    }
}

tasks.named("preBuild") {
    dependsOn(packageCompanionWebAssets)
    dependsOn(generateCandidateIdentity)
}

// Full APK assembly is an expensive frozen-candidate lane. Recheck after packaging so ordinary
// stale/dirty builds cannot be presented as evidence merely because an earlier marker survived.
// This is a trusted-operator procedural guard, not an adversarial concurrent-build attestation.
tasks.matching { it.name == "assembleMockDebug" || it.name == "assembleDjiDebug" }.configureEach {
    doLast {
        val freshCommit = gitOutput("rev-parse", "--verify", "HEAD^{commit}").trim()
        val freshStatus =
            gitOutput(
                "status",
                "--porcelain=v1",
                "--untracked-files=all",
                "--ignore-submodules=none",
            )
        val identityDirectory = generatedCandidateIdentityDirectory.get().asFile
        val candidateAssetsRoot = generatedCandidateAssets.get().asFile
        val files = identityDirectory.listFiles().orEmpty().associateBy { it.name }
        require(freshStatus.isBlank()) { "mock APK assembly requires a clean frozen candidate" }
        require(candidateAssetsRoot.listFiles().orEmpty().map { it.name }.toSet() ==
            setOf("companion-candidate")) {
            "candidate assets root differs from its exact allowlist"
        }
        require(files.keys == setOf("commit.txt", "worktree-state.txt")) {
            "candidate identity directory differs from its exact allowlist"
        }
        require(files.getValue("commit.txt").readText(Charsets.US_ASCII).trim() == freshCommit) {
            "packaged candidate commit no longer matches HEAD"
        }
        require(
            files.getValue("worktree-state.txt").readText(Charsets.US_ASCII).trim() == "clean",
        ) {
            "packaged candidate was not generated from a clean worktree"
        }
    }
}

tasks.matching { it.name == "assembleDjiDebug" }.configureEach {
    dependsOn(verifyDjiArm64NativeRuntime)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":vision-opencv-android"))
    // Observation-only decoded frames and LiveVisionBridge. This surface cannot name DJI control.
    implementation("com.durendal.droneagent:drone-observation:local")
    "djiImplementation"(project(":commissioning-network"))
    "djiImplementation"(project(":console-adapter-dji"))
    "djiImplementation"("com.durendal.droneagent:adapter-dji:local")
    "mockImplementation"(project(":console-adapter-mock"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.okhttp)
}

val verifyOpenCvRuntimeDependencies by tasks.registering {
    group = "verification"
    description = "Rejects desktop OpenCV artifacts from the Android host runtime."
    doLast {
        val components =
            configurations.getByName("mockDebugRuntimeClasspath")
                .incoming.resolutionResult.allComponents
                .map { it.id.displayName }
                .toSet()
        require("org.opencv:opencv:4.9.0" in components) {
            "host runtime must contain the official OpenCV Android 4.9.0 distribution"
        }
        val forbidden = components.filter { component ->
            component.contains("vision-opencv-desktop", ignoreCase = true) ||
                component.contains("openpnp", ignoreCase = true)
        }
        require(forbidden.isEmpty()) {
            "host runtime contains desktop OpenCV dependencies: ${forbidden.sorted()}"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyOpenCvRuntimeDependencies)
}

tasks.withType<Test>().configureEach {
    systemProperty("companion.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}
