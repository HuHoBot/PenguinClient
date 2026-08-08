pluginManagement {
    plugins {
        kotlin("plugin.lombok") version "2.2.20"
    }
}
include(":common-Bot")
project(":common-Bot").projectDir = file("common/Bot")

include(":server-Spigot")
project(":server-Spigot").projectDir = file("server/Spigot")

rootProject.name = "HuHoBotPenguin"
