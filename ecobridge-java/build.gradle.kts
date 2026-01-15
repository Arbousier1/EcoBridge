import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

buildscript {
    repositories { 
        mavenCentral()
        gradlePluginPortal() 
    }
    dependencies {
<<<<<<< HEAD
        // 针对 Java 25 优化的 ASM 字节码处理工具
=======
        classpath("org.ow2.asm:asm:9.9.1") 
>>>>>>> 9ade8016b0cd1dcd23a1b00d387e2574b3b82711
        classpath("org.ow2.asm:asm-commons:9.9.1")
    }
}

plugins {
    `java-library`
<<<<<<< HEAD
    // 2026年 1月最新稳定版 Shadow 插件
=======
>>>>>>> 9ade8016b0cd1dcd23a1b00d387e2574b3b82711
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
<<<<<<< HEAD
    
    // NightExpress 官方仓库 (CoinsEngine/NightCore)
    maven("https://repo.nightexpressdev.com/releases") 
    
    // UltimateShop 官方 Maven 仓库
    maven("https://repo.lanink.cn/repository/maven-public/")
    
    // 备用仓库
    maven("https://jitpack.io")
    
=======
    maven("https://repo.nightexpressdev.com/releases") 
>>>>>>> 9ade8016b0cd1dcd23a1b00d387e2574b3b82711
    flatDir { dirs("libs") }
}

dependencies {
<<<<<<< HEAD
    // 1. 本地依赖 (将任何无法从 Maven 下载的 Jar 放入 libs 目录)
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))

    // 2. 外部插件 API
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    
    // CoinsEngine 及其前置
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")
    
    // UltimateShop API (根据 2026 最新版)
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")

    // 3. 运行时打包依赖 (ShadowJar)
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("com.zaxxer:HikariCP:6.2.1") 
    
    // Gson 由 Paper 提供，改为 compileOnly 避免重定位冲突风险
    compileOnly("com.google.code.gson:gson:2.12.1")
    
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("redis.clients:jedis:7.2.0")
=======
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))

    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT") 
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")

    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
    implementation("com.zaxxer:HikariCP:6.2.0") 
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("redis.clients:jedis:7.2.0") // 修正版本号建议：Jedis 目前主流稳定版为 5.x
>>>>>>> 9ade8016b0cd1dcd23a1b00d387e2574b3b82711
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
    
<<<<<<< HEAD
    // 依赖重定位 (Relocation) 避免插件冲突
    val prefix = "top.ellan.ecobridge.libs"
    relocate("com.zaxxer.hikari", "$prefix.hikari")
    relocate("org.mariadb.jdbc", "$prefix.mariadb")
    relocate("com.github.benmanes.caffeine", "$prefix.caffeine")
    
    // 仅重定位 Jedis 核心包
    relocate("redis.clients", "$prefix.jedis")
    
    // 打包 Rust 编译产物 (*.dll, *.so, *.dylib)
=======
    relocate("com.zaxxer.hikari", "top.ellan.ecobridge.libs.hikari")
    relocate("org.mariadb.jdbc", "top.ellan.ecobridge.libs.mariadb")
    relocate("com.github.benmanes.caffeine", "top.ellan.ecobridge.libs.caffeine")
    relocate("redis.clients", "top.ellan.ecobridge.libs.jedis")
    relocate("org.apache.commons.pool2", "top.ellan.ecobridge.libs.commons.pool2")
    relocate("org.json", "top.ellan.ecobridge.libs.json")
    
>>>>>>> 9ade8016b0cd1dcd23a1b00d387e2574b3b82711
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
