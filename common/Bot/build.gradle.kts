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
    // 由 includeBuild("deps/qqpd-bot-java") 的本地源码替换，不再从 Maven 拉取
    implementation("io.github.kloping:bot-qqpd-java:1.5.3-L4")
    // 编译期直接使用的类（includeBuild 替换后不再透传传递依赖，需显式声明）
    implementation("com.alibaba:fastjson:2.0.32")
    implementation("io.github.kloping:SpringTool:0.6.4")
    testImplementation(kotlin("test"))
}
repositories {
    mavenCentral()
}