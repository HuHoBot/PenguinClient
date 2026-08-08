plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") apply false
    kotlin("plugin.lombok")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8)) // 设置 JDK 8
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.github.kloping:bot-qqpd-java:1.5.3-L4")
    testImplementation(kotlin("test"))
}
repositories {
    mavenCentral()
}