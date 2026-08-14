plugins {
    base
}

// Keep the root build useful as the clean-clone acceptance command. Android
// projects participate only when settings.gradle.kts has admitted them.
tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" })
}
