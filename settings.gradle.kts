pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "sonar"

include(
    "sonar-core",
    "sonar-batfish",
    "sonar-jev",
    "sonar-dataset",
    "sonar-app",
    "sonar-web",
    "sonar-cli",
)
