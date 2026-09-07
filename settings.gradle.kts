pluginManagement {
    repositories {
        google {
            mavenContent {

            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
            }
        }
        mavenCentral()
    }
}

rootProject.name = "apv-text-editor"
include(":composeApp")
