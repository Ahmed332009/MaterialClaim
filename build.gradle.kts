plugins {
    java
}

group = "dev.keepclaims"
version = "0.4.6"

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

// ---------------------------------------------------------------------------
// ONE repository, TWO plugins, TWO jars.
//
//   src/shared  - code that is identical in both plugins (protection listener,
//                 payment, settings, trust list, helpers). Compiled into both.
//   src/chunk   - MaterialClaim_Chunk: the original chunk-by-chunk claiming.
//   src/block   - MaterialClaim_Block: block claiming with a golden shovel.
//
// Both jars live in the SAME package (dev.keepclaims) because they share the
// shared classes. That is safe as long as only one is installed: they are two
// separate plugins that both claim land, so running both on one server would
// fight over the same ground.
// ---------------------------------------------------------------------------

val sharedSrc = file("src/shared/java")

val chunk by sourceSets.creating {
    java.setSrcDirs(listOf("src/chunk/java", sharedSrc))
    resources.setSrcDirs(listOf("src/chunk/resources"))
    compileClasspath += sourceSets["main"].compileClasspath
}

val block by sourceSets.creating {
    java.setSrcDirs(listOf("src/block/java", sharedSrc))
    resources.setSrcDirs(listOf("src/block/resources"))
    compileClasspath += sourceSets["main"].compileClasspath
}

// The default 'main' source set is not used; keep it out of the build.
sourceSets["main"].java.setSrcDirs(emptyList<String>())
sourceSets["main"].resources.setSrcDirs(emptyList<String>())

// ${version} in plugin.yml must be expanded in EACH variant's resources. The default
// 'main' processResources task is not used by the variants, so it is not enough to
// configure it here: the chunk and block tasks need it too.
tasks.named<ProcessResources>(chunk.processResourcesTaskName) {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.named<ProcessResources>(block.processResourcesTaskName) {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

val chunkJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds the MaterialClaim_Chunk plugin jar."
    archiveBaseName.set("MaterialClaim_Chunk")
    archiveClassifier.set("")
    from(chunk.output)
    dependsOn(tasks.named(chunk.classesTaskName))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val blockJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds the MaterialClaim_Block plugin jar."
    archiveBaseName.set("MaterialClaim_Block")
    archiveClassifier.set("")
    from(block.output)
    dependsOn(tasks.named(block.classesTaskName))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("build") {
    dependsOn(chunkJar, blockJar)
}

// `./gradlew jar` is what the release workflow runs; make it produce both jars.
tasks.named("jar") {
    dependsOn(chunkJar, blockJar)
    enabled = false
}
