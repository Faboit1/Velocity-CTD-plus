plugins {
    `java-library`
}

// The plugin runs on whatever JVM the backend server uses, which is not necessarily the one this
// repository builds the proxy with. Target the oldest JVM modern Paper supports.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

repositories {
    // Declared in settings.gradle.kts; listed here only for readers.
}

dependencies {
    // Compiled against 1.21.x rather than the newest API so the jar still loads on Java 21 servers;
    // everything used here is long-stable Bukkit API that newer Paper keeps working.
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // packetevents stays a separate server plugin rather than being shaded in: it is what carries
    // support for new Minecraft versions, and keeping it separate lets a server update it without
    // waiting on a new build of this plugin.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}

tasks {
    jar {
        archiveBaseName.set("VelocitySeamless")
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
