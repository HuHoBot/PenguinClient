plugins {
    kotlin("jvm") version "2.2.20" apply false
    id("com.gradleup.shadow") version "8.3.11" apply false
}

allprojects {
    group = "cn.huohuas001"
    version = "1.0.2"

    repositories {
        // Resolve canonical artifacts from Maven Central first. Module-specific
        // repositories (for example Nukkit-MOT's Lanink repository) are added
        // by each module and must not be masked by a transient mirror failure.
        mavenCentral()
    }
}
