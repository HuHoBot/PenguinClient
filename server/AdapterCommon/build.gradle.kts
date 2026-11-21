plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":common-Bot"))
    api("org.yaml:snakeyaml:1.33")
    testImplementation(kotlin("test"))
}


kotlin {
    jvmToolchain(8)
}
