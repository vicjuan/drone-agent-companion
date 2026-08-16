plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":vision-opencv-core"))
    implementation("org.openpnp:opencv:4.9.0-0")

    testImplementation(libs.junit)
}
