pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("plugin.lombok") version "2.2.20"
    }
}
includeBuild("deps/qqpd-bot-java")

include(":common-Bot")
project(":common-Bot").projectDir = file("common/Bot")

include(":server-Spigot")
project(":server-Spigot").projectDir = file("server/Spigot")

rootProject.name = "HuHoBotPenguin"
