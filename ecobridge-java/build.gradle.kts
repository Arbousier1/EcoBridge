import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // ASM 9.9.1: 完美支持 Java 25 预览版字节码
        classpath("org.ow2.asm:asm-commons:9.9.1")
    }
}

plugins {
    `java-library`
    // 严格保留 Shadow 9.3.1
    id("com.gradleup.shadow") version "9.3.1"
}

group = "top.ellan"
version = "1.0-SNAPSHOT"

// --- [jextract 自动化配置逻辑] ---
val rustHeaderFile = file("${projectDir}/../ecobridge-rust/ecobridge_rust.h")
val generatedSourceDir = layout.buildDirectory.dir("generated/sources/jextract")
val targetPackage = "top.ellan.ecobridge.gen"

fun findJextract(): String {
    val os = org.gradle.internal.os.OperatingSystem.current()
    val binaryName = if (os.isWindows) "jextract.bat" else "jextract"
    
    // 优先读取环境变量 (针对 GitHub Actions)
    val envHome = System.getenv("JEXTRACT_HOME")
    if (!envHome.isNullOrBlank()) {
        val path = file("$envHome/bin/$binaryName")
        if (path.exists()) return path.absolutePath
    }

    // 其次读取 local.properties (针对本地开发)
    val localPropsFile = file("local.properties")
    if (localPropsFile.exists()) {
        val props = Properties()
        localPropsFile.inputStream().use { props.load(it) }
        val localHome = props.getProperty("jextract.home")
        if (localHome != null) {
            val possiblePaths = listOf(file("$localHome/bin/$binaryName"), file("$localHome/$binaryName"))
            for (path in possiblePaths) if (path.exists()) return path.absolutePath
        }
    }
    
    return binaryName // 降级为系统 PATH 中的 jextract
}

val generateBindings = tasks.register<Exec>("generateBindings") {
    group = "build"
    description = "使用 jextract 自动从 Rust 头文件生成 Java FFM 绑定。"

    doFirst {
        if (!rustHeaderFile.exists()) {
            throw GradleException("❌ 错误：未找到 Rust 头文件: ${rustHeaderFile.absolutePath}")
        }
        generatedSourceDir.get().asFile.mkdirs()
    }

    commandLine(
        findJextract(),
        "--output", generatedSourceDir.get().asFile.absolutePath,
        "--target-package", targetPackage,
        "--header-class-name", "ecobridge_rust_h",
        "--library", "ecobridge_rust",
        rustHeaderFile.absolutePath
    )

    inputs.file(rustHeaderFile)
    outputs.dir(generatedSourceDir)
}

// --- [Java 环境与工具链] ---
java {
    toolchain { 
        languageVersion.set(JavaLanguageVersion.of(25)) 
    }
}

sourceSets {
    main {
        // ✅ 核心修复：注册 jextract 输出。这不仅解决了包名不存在问题，
        // 还会自动让 compileJava 任务依赖于 generateBindings。
        java.srcDir(generateBindings)
    }
}

repositories {
    mavenCentral()
    // 🔥 关键：Jackson 3.0 / 2.20 目前主要通过 Sonatype 仓库分发
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://oss.sonatype.org/content/repositories/releases/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://repo.lanink.cn/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    flatDir { dirs("libs") }
}

dependencies {
    // 严格保留：Paper API
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))

    // 🔥 Jackson 3.0 全家桶配置 (严格遵循迁移指南)
    implementation(platform("tools.jackson:jackson-bom:3.0.0"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.core:jackson-core")
    // 注解保持旧坐标，BOM 会自动解析到匹配的 2.20 系列
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    // 🔥 2026 最新稳定版数据库/缓存库
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("redis.clients:jedis:7.2.0")
    
    compileOnly("com.google.code.gson:gson:2.13.2")

    // 测试
    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<JavaCompile> {
    // 再次显式依赖，确保并行构建时的安全性
    dependsOn(generateBindings)
    
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
    val prefix = "top.ellan.ecobridge.libs"
    
    // ✅ 必须重定向依赖，否则会导致插件冲突
    relocate("tools.jackson", "$prefix.jackson")
    relocate("com.fasterxml.jackson.annotation", "$prefix.jackson.annotations")
    relocate("com.zaxxer.hikari", "$prefix.hikari")
    relocate("org.mariadb.jdbc", "$prefix.mariadb")
    relocate("com.github.benmanes.caffeine", "$prefix.caffeine")
    relocate("redis.clients", "$prefix.jedis")
    
    // 打包资源文件，包括 native 库
    from("src/main/resources") {
        include("*.dll", "*.so", "*.dylib", "natives/**")
    }
    
    // 合并服务发现文件（对 JDBC 和 Jackson 很重要）
    mergeServiceFiles()
}

tasks.withType<ProcessResources> {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}