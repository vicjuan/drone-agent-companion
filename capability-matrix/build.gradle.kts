plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.durendal.droneagent:core:local")
    implementation(libs.kotlinx.serialization.json)

    testImplementation("com.durendal.droneagent:adapter-mock:local")
    testImplementation(libs.junit)
}

val canonicalMatrix = rootProject.layout.projectDirectory.file("config/capability-matrix/g520-stack.json")

tasks.processResources {
    from(canonicalMatrix) {
        into("capability-matrix")
    }
}

tasks.test {
    systemProperty("companion.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}

tasks.register<JavaExec>("generateCapabilityMatrixMarkdown") {
    group = "documentation"
    description = "Render docs/capability-matrix.md from the canonical G520 JSON source"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(
        "com.durendal.droneagent.companion.capability.GenerateCapabilityMatrixMarkdown",
    )
    args(
        canonicalMatrix.asFile.absolutePath,
        rootProject.layout.projectDirectory.file("docs/capability-matrix.md").asFile.absolutePath,
    )
}
