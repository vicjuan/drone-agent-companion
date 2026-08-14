plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.durendal.droneagent:vision:local")

    testImplementation(libs.junit)
}
