@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.velocityctd.com/releases") {
            name = "velocityctdReleases"
        }
        maven("https://repo.codemc.io/repository/maven-releases/") {
            name = "codemc"
        }
    }
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "velocity-ctd"

sequenceOf(
    "api",
    "native",
    "proxy",
    "bootstrap",
).forEach {
    val project = ":velocity-$it"
    include(project)
    project(project).projectDir = file(it)
}

// Permission integration modules
val permissionIntegrationSpi = ":velocity-permission-integration-spi"
include(permissionIntegrationSpi)
project(permissionIntegrationSpi).projectDir = file("permission-integration/spi")

val permissionIntegrationLuckperms = ":velocity-permission-integration-luckperms"
include(permissionIntegrationLuckperms)
project(permissionIntegrationLuckperms).projectDir = file("permission-integration/luckperms")

// Companion Paper/Folia plugin for seamless server switching
val seamlessPaperPlugin = ":velocity-seamless-paper"
include(seamlessPaperPlugin)
project(seamlessPaperPlugin).projectDir = file("paper-plugin")

// Include Configurate 3
val deprecatedConfigurateModule = ":deprecated-configurate3"
include(deprecatedConfigurateModule)
project(deprecatedConfigurateModule).projectDir = file("proxy/deprecated/configurate3")
