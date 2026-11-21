plugins {
    java
}

group = "dev.keepclaims"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Compiled against the 1.21.11 API. Paper keeps plugins built this way working on newer versions.
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// Standard Maven layout: sources in src/main/java, resources in src/main/resources.
// Do NOT override sourceSets here. A previous version pointed them at the repository
// root, which matched no .java files and produced an empty, unusable jar.

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("KeepClaims")
}
