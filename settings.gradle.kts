// FxxkMoondrop · Gradle 8.9 + AGP 8.5.2 + Kotlin 1.9.22
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
    }
}
rootProject.name = "FxxkMoondrop"
include(":app")
