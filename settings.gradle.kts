pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JetBrains public Kotlin repo (for stable + EAP if needed)
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlin/dev") }
    }
}

rootProject.name = "ThunderNotes"
include(":app")
