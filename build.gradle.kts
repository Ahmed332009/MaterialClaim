plugins {
    java
}

group = "dev.keepclaims"
version = "0.2.0"

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

// Flat layout: all files sit in the repository root so they can be uploaded from a phone.
sourceSets {
    main {
        java {
            setSrcDirs(listOf("."))
            include("*.java")
        }
        resources {
            setSrcDirs(listOf("."))
            include("plugin.yml", "config.yml")
        }
    }
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("MaterialClaim")
}
