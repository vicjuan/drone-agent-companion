plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":console-server"))
    implementation("com.durendal.droneagent:adapter-mock:local")

    testImplementation(libs.junit)
}

application {
    mainClass.set("com.durendal.droneagent.companion.console.runner.MainKt")
    applicationName = "drone-console"
}
