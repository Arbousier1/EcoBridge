import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

buildscript {
    repositories { 
        mavenCentral()
        gradlePluginPortal() 
    }
    dependencies {
        // 确保 ASM 版本足够高以支持 Java 25 字节码处理
        classpath("org.ow2.asm:asm:9.9.1") 
        classpath("org.ow2.asm:asm-commons:9.9.1")
    }
}

plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.6" // 推荐使用最新的稳定版 Shadow 插件
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
    // 关键：NightExpress 官方仓库
    maven("https://repo.nightexpressdev.com/releases") 
    // 确保 Gradle 扫描 libs 目录及其子目录
    flatDir { dirs("libs") }
}

dependencies {
    // 1. 本地依赖集成
    // 使用 fileTree 递归扫描 libs 下所有子目录
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))

    // 2. 外部插件 API
    // Paper API
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT") // 修正为 1.21.4 (对应 1.21.11 核心通常基于此 API)
    // CoinsEngine 及其前置 NightCore (必须为 compileOnly)
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")

    // 3. 运行时打包依赖 (ShadowJar 内容)
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
    implementation("com.zaxxer:HikariCP:6.2.0") // 适配 Java 21+ 的 HikariCP 版本
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    
    // [新增] Redis 客户端 (Jedis)
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
    
    // 依赖重定位 (Relocation) 避免与其它插件冲突
    relocate("com.zaxxer.hikari", "top.ellan.ecobridge.libs.hikari")
    relocate("org.mariadb.jdbc", "top.ellan.ecobridge.libs.mariadb")
    relocate("com.github.benmanes.caffeine", "top.ellan.ecobridge.libs.caffeine")
    
    // [新增] Redis 相关库重定位 (非常重要，Jedis 极其容易冲突)
    relocate("redis.clients", "top.ellan.ecobridge.libs.jedis")
    relocate("org.apache.commons.pool2", "top.ellan.ecobridge.libs.commons.pool2")
    relocate("org.json", "top.ellan.ecobridge.libs.json")
    
    // 自动打包 Rust 编译产物 (*.dll, *.so, *.dylib)
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