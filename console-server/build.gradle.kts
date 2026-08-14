plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":console-protocol"))
    implementation("com.durendal.droneagent:core:local")
    implementation("com.durendal.droneagent:drone-actuation:local")
    implementation("com.durendal.droneagent:gateway:local")

    testImplementation(libs.junit)
}
