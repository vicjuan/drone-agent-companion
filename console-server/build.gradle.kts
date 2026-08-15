plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":console-protocol"))
    implementation(project(":capability-matrix"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation("com.durendal.droneagent:core:local")
    implementation("com.durendal.droneagent:drone-actuation:local")
    implementation("com.durendal.droneagent:gateway:local")

    testImplementation("com.durendal.droneagent:adapter-mock:local")
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("companion.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}
