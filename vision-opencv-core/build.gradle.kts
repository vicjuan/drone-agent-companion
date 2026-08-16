plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.durendal.droneagent:vision:local")
    compileOnly("org.openpnp:opencv:4.9.0-0")

    testImplementation(libs.junit)
    testImplementation("org.openpnp:opencv:4.9.0-0")
}
