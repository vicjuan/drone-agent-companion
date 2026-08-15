plugins {
    base
    // Load the Kotlin JVM plugin once for all companion subprojects. Keeping
    // AGP out of this block preserves the no-Android-SDK pure-JVM lane.
    alias(libs.plugins.kotlin.jvm) apply false
}

// Keep the root build useful as the clean-clone acceptance command. Android
// projects participate only when settings.gradle.kts has admitted them.
tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" })
}
