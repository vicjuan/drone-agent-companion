plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":console-protocol"))
    implementation(project(":capability-matrix"))
    implementation("com.durendal.droneagent:core:local")
    implementation("com.durendal.droneagent:drone-actuation:local")
    implementation("com.durendal.droneagent:gateway:local")

    testImplementation("com.durendal.droneagent:adapter-mock:local")
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("companion.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}
