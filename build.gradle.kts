// Root build script. Module configuration lives in :app/build.gradle.kts.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
