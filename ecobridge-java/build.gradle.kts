import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

buildscript {
    repositories { 
        mavenCentral()
        gradlePluginPortal() 
    }
    dependencies {
        classpath("org.ow2.asm:asm:9.9.1") 
        classpath("org.ow2.asm:asm-commons:9.9.1")
    }
}

plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.6" 
}

group = "top.ellan"
version = "1.0-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

repositories {
    mavenCentral()
    // 关键修复：NightCore 依赖的 UniversalScheduler 托管在 JitPack
    maven("https://jitpack.io") 
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases") 
    flatDir { dirs("libs") }
}

dependencies {
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))

    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT") 
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")

    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
    implementation("com.zaxxer:HikariCP:6.2.0") 
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("redis.clients:jedis:7.2.0") // 修正版本号建议：Jedis 目前主流稳定版为 5.x
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf(
        "--enable-preview", 
        "-Xlint:unchecked", 
        "-Xlint:-preview"
    ))
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    
    relocate("com.zaxxer.hikari", "top.ellan.ecobridge.libs.hikari")
    relocate("org.mariadb.jdbc", "top.ellan.ecobridge.libs.mariadb")
    relocate("com.github.benmanes.caffeine", "top.ellan.ecobridge.libs.caffeine")
    relocate("redis.clients", "top.ellan.ecobridge.libs.jedis")
    relocate("org.apache.commons.pool2", "top.ellan.ecobridge.libs.commons.pool2")
    relocate("org.json", "top.ellan.ecobridge.libs.json")
    
    from("src/main/resources") {
        include("*.dll", "*.so", "*.dylib")
    }

    mergeServiceFiles()
}

tasks.withType<ProcessResources> {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
