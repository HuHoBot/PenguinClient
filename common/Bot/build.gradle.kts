plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") apply false
    kotlin("plugin.lombok")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8)) // 设置 JDK 8
    }

    // qqpd-bot-java is kept as a Git submodule and is a Maven project.
    // Compile its sources as part of this module so local submodule changes
    // are used immediately without importing its Gradle build.
    sourceSets {
        named("main") {
            java.srcDir(rootProject.file("deps/qqpd-bot-java/src/main/java"))
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))

    // Dependencies declared by deps/qqpd-bot-java/pom.xml（已同步上游 1.5.4-R4）。
    implementation("io.github.kloping:SpringTool:0.7.2-L2")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    // SDK 的 StandaloneLogging / ClrConverter 直接引用 logback，不能再使用 slf4j-nop。
    // 上游 pom 写的是 1.5.20，但那是 Java 11 字节码；本项目仍以 Java 8 为运行基线
    // （toolchain 8 / --release 8），所以固定到同系列最后一个 Java 8 版本。
    // SDK 只用到 LoggerContext / ConsoleAppender / PatternLayoutEncoder 等长期稳定的 API。
    implementation("ch.qos.logback:logback-classic:1.3.14")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jsoup:jsoup:1.15.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")

    // Used by both the submodule sources and this module's Kotlin sources.
    implementation("com.alibaba:fastjson:2.0.32")

    // 把扫码登录 URL 渲染成终端二维码（core 3.5.x 目标字节码为 Java 8）。
    implementation("com.google.zxing:core:3.5.3")

    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")

    testImplementation(kotlin("test"))
}

repositories {
    mavenCentral()
}
