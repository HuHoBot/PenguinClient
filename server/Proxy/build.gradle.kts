plugins {
    java
    kotlin("jvm")
    kotlin("kapt")
    id("com.gradleup.shadow")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    mavenCentral()
}

version = rootProject.version

dependencies {
    implementation(project(":server-AdapterCommon"))
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("net.md-5:bungeecord-api:1.16-R0.4") {
        exclude(group = "net.md-5", module = "bungeecord-protocol")
    }
    implementation(kotlin("stdlib"))
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))
kotlin.jvmToolchain(17)

tasks.processResources {
    val pluginVersion = rootProject.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching(listOf("bungee.yml", "velocity-plugin.json")) {
        expand("version" to pluginVersion)
    }
}

val gatherJar by tasks.registering(Copy::class) {
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("gather-jar"))
}

tasks.shadowJar {
    archiveFileName.set("HuHoBot-Penguin_Proxy-${project.version}.jar")
    finalizedBy(gatherJar)
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
