plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.durendal.droneagent.companion.console.dji"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // DJI/Android platform calls are kept behind injected seams in local unit tests.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":console-protocol"))
    api(project(":console-server"))
    api("com.durendal.droneagent:adapter-dji:local")
    implementation("com.durendal.droneagent:drone-actuation:local")

    testImplementation(project(":capability-matrix"))
    testImplementation(libs.junit)
}
