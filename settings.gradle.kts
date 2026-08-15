pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Keep the mirror as a final fallback instead of allowing a mirror-side
        // 5xx response to block the canonical plugin repositories.
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
    }
    plugins {
        kotlin("plugin.lombok") version "2.2.20"
    }
}

include(":common-Bot")
project(":common-Bot").projectDir = file("common/Bot")

include(":server-AdapterCommon")
project(":server-AdapterCommon").projectDir = file("server/AdapterCommon")

include(":server-Spigot")
project(":server-Spigot").projectDir = file("server/Spigot")

include(":server-Allay")
project(":server-Allay").projectDir = file("server/Allay")

include(":server-Nukkit")
project(":server-Nukkit").projectDir = file("server/Nukkit")

include(":server-Proxy")
project(":server-Proxy").projectDir = file("server/Proxy")

rootProject.name = "HuHoBotPenguin"
