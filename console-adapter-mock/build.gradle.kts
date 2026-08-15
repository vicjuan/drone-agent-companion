plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":console-protocol"))
    api(project(":console-server"))
    api("com.durendal.droneagent:adapter-mock:local")

    testImplementation(project(":capability-matrix"))
    testImplementation(libs.junit)
}
