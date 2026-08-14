plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.durendal.droneagent.companion.host"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.durendal.droneagent.companion.host"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-s0"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":console-server"))
    implementation(project(":vision-opencv-android"))
    implementation("com.durendal.droneagent:drone-observation:local")
    implementation("com.durendal.droneagent:adapter-mock:local")
}
