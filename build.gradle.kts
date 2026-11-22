plugins {
    java
}

group = "dev.keepclaims"
version = "0.3.3"

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
// Do NOT point sourceSets at the repository root. The root copies are only the phone-upload
// staging area; the real sources live under src/. Overriding sourceSets to "." makes Gradle
// compile the stale root copies instead and drop plugin.yml from the jar.

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("MaterialClaim")
}
