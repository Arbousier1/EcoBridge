import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties

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

// --- [jextract 自动化配置逻辑] ---
val rustHeaderFile = file("${projectDir}/../ecobridge-rust/ecobridge_rust.h")
val generatedSourceDir = layout.buildDirectory.dir("generated/sources/jextract")
val targetPackage = "top.ellan.ecobridge.gen"

// 🔍 智能查找 jextract 启动脚本
fun findJextract(): String {
    val os = org.gradle.internal.os.OperatingSystem.current()
    val binaryName = if (os.isWindows) "jextract.bat" else "jextract"
    
    // 1. 优先读取 local.properties
    val localPropsFile = file("local.properties")
    if (localPropsFile.exists()) {
        val props = Properties()
        localPropsFile.inputStream().use { props.load(it) }
        val localHome = props.getProperty("jextract.home")
        if (localHome != null) {
            val possiblePaths = listOf(
                file("$localHome/bin/$binaryName"),
                file("$localHome/$binaryName")
            )
            for (path in possiblePaths) {
                if (path.exists()) {
                    println("✅ [Local] 找到 jextract 脚本: ${path.absolutePath}")
                    return path.absolutePath
                }
            }
        }
    }

    // 2. 尝试环境变量
    val envHome = System.getenv("JEXTRACT_HOME")
    if (envHome != null) {
        val path = file("$envHome/bin/$binaryName")
        if (path.exists()) return path.absolutePath
    }

    // 3. 尝试 JAVA_HOME
    val javaHome = System.getProperty("java.home")
    val jdkPath = file("$javaHome/bin/$binaryName")
    if (jdkPath.exists()) return jdkPath.absolutePath

    return binaryName
}

// 核心任务：自动化生成 Java 绑定
val generateBindings = tasks.register<Exec>("generateBindings") {
    group = "build"
    description = "使用 jextract 自动从 Rust 头文件生成 Java FFM 绑定。"

    doFirst {
        if (!rustHeaderFile.exists()) {
            throw GradleException("未找到 Rust 头文件: ${rustHeaderFile.absolutePath}。请先编译 Rust 项目。")
        }
        generatedSourceDir.get().asFile.mkdirs()
    }

    commandLine(
        findJextract(),
        "--output", generatedSourceDir.get().asFile.absolutePath,
        "--target-package", targetPackage,
        // 🔥🔥🔥 [关键配置] 强制指定辅助类名为 ecobridge_rust_h 🔥🔥🔥
        // 这样生成的 MarketConfig.java 才能正确找到它
        "--header-class-name", "ecobridge_rust_h",
        "--library", "ecobridge_rust",
        rustHeaderFile.absolutePath
    )

    inputs.file(rustHeaderFile)
    outputs.dir(generatedSourceDir)
}

// --- [Java 编译与工具链配置] ---
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

// 将生成的代码加入源代码集
sourceSets {
    main {
        // ✅ 修正点：直接传入任务实例
        // Gradle 会自动解析任务的 outputs 目录，并自动添加 dependsOn 依赖
        java.srcDir(generateBindings)
    }
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://repo.lanink.cn/repository/maven-public/")
    flatDir { dirs("libs") }
}

dependencies {
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")
    
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("redis.clients:jedis:7.2.0")
    compileOnly("com.google.code.gson:gson:2.12.1")

    // JUnit 5 测试依赖
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    // 显式依赖确保顺序（虽然 srcDir(task) 已经处理了，但加这一行双重保险）
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
    relocate("com.zaxxer.hikari", "$prefix.hikari")
    relocate("org.mariadb.jdbc", "$prefix.mariadb")
    relocate("com.github.benmanes.caffeine", "$prefix.caffeine")
    relocate("redis.clients", "$prefix.jedis")
    
    from("src/main/resources") {
        include("*.dll", "*.so", "*.dylib", "natives/**")
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
