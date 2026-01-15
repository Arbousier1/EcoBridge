import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

buildscript {
    repositories { 
        mavenCentral()
        gradlePluginPortal() 
    }
    dependencies {
        // 针对 Java 25 优化的 ASM 字节码处理工具
        classpath("org.ow2.asm:asm-commons:9.9.1")
    }
}

plugins {
    `java-library`
    // 2026年 1月最新稳定版 Shadow 插件
    id("com.gradleup.shadow") version "8.3.6" 
}

group = "top.ellan"
version = "1.0-SNAPSHOT"

java {
    // 强制指定 Java 25 工具链，驱动 Project Panama (FFM)
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    
    // NightExpress 官方仓库 (CoinsEngine/NightCore)
    maven("https://repo.nightexpressdev.com/releases") 
    
    // UltimateShop 官方 Maven 仓库
    maven("https://repo.lanink.cn/repository/maven-public/")
    
    // 备用仓库
    maven("https://jitpack.io")
    
    flatDir { dirs("libs") }
}

dependencies {
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
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
    // 开启 Java 25 预览特性，允许使用内存段 (MemorySegment) 和原生链接器
    options.compilerArgs.addAll(listOf(
        "--enable-preview", 
        "-Xlint:unchecked", 
        "-Xlint:-preview"
    ))
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    
    // 依赖重定位 (Relocation) 避免插件冲突
    val prefix = "top.ellan.ecobridge.libs"
    relocate("com.zaxxer.hikari", "$prefix.hikari")
    relocate("org.mariadb.jdbc", "$prefix.mariadb")
    relocate("com.github.benmanes.caffeine", "$prefix.caffeine")
    
    // 仅重定位 Jedis 核心包
    relocate("redis.clients", "$prefix.jedis")
    
    // 打包 Rust 编译产物 (*.dll, *.so, *.dylib)
    from("src/main/resources") {
        include("*.dll", "*.so", "*.dylib")
    }

    mergeServiceFiles()
}

// 确保 ProcessResources 任务能正确处理资源过滤
tasks.withType<ProcessResources> {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}