// FxxkMoondrop · Gradle 8.9 + AGP 8.6.1 + Kotlin 2.3.21
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
