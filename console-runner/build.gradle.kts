plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":console-adapter-mock"))

    testImplementation(project(":capability-matrix"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.junit)
}

application {
    mainClass.set("com.durendal.droneagent.companion.console.runner.MainKt")
    applicationName = "drone-console"
}

val webConsoleDirectory = rootProject.layout.projectDirectory.dir("web-console")
val installWebConsoleDependencies by tasks.registering(Exec::class) {
    workingDir(webConsoleDirectory)
    commandLine("npm", "ci", "--ignore-scripts")
    inputs.files(
        webConsoleDirectory.file("package.json"),
        webConsoleDirectory.file("package-lock.json"),
    )
    outputs.dir(webConsoleDirectory.dir("node_modules"))
}

val buildWebConsole by tasks.registering(Exec::class) {
    dependsOn(installWebConsoleDependencies)
    workingDir(webConsoleDirectory)
    commandLine("npm", "run", "build")
    inputs.dir(webConsoleDirectory.dir("src"))
    inputs.dir(webConsoleDirectory.dir("static"))
    inputs.dir(webConsoleDirectory.dir("scripts"))
    inputs.file(webConsoleDirectory.file("tsconfig.json"))
    inputs.file(rootProject.layout.projectDirectory.file("config/capability-matrix/g520-stack.json"))
    outputs.dir(webConsoleDirectory.dir("dist"))
}

tasks.named<JavaExec>("run") {
    dependsOn(buildWebConsole)
    // Main resolves the development SPA and default audit path from the process working directory.
    // Gradle otherwise launches JavaExec from this subproject, making the documented root-level
    // `./gradlew :console-runner:run` command look under console-runner/web-console/dist.
    workingDir(rootProject.layout.projectDirectory)
}
