import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    `java-library`
    // Shadow 9.3.1 完美支持 Java 25，无需 ASM 补丁
    id("com.gradleup.shadow") version "9.3.1"
}

group = "top.ellan"
version = "1.0-SNAPSHOT"

// --- [jextract 自动化配置逻辑] ---
val rustHeaderFile = file("${projectDir}/../ecobridge-rust/ecobridge_rust.h")
val generatedSourceDir = layout.buildDirectory.dir("generated/sources/jextract")
val targetPackage = "top.ellan.ecobridge.gen"

// 🔍 智能查找 jextract (支持 Windows/Linux/CI)
fun findJextract(): String {
    val os = org.gradle.internal.os.OperatingSystem.current()
    // Windows 必须用 .bat 启动脚本，Linux/Mac 直接用二进制文件
    val binaryName = if (os.isWindows) "jextract.bat" else "jextract"
    
    // 1. 优先读取 local.properties (本地开发人员专用)
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
                    println("✅ [Local] 找到 jextract: ${path.absolutePath}")
                    return path.absolutePath
                }
            }
        }
    }

    // 2. 读取环境变量 JEXTRACT_HOME (GitHub Actions / CI 专用)
    val envHome = System.getenv("JEXTRACT_HOME")
    if (envHome != null) {
        val path = file("$envHome/bin/$binaryName")
        if (path.exists()) {
            println("✅ [CI] 环境变量找到 jextract: ${path.absolutePath}")
            return path.absolutePath
        }
    }

    // 3. 尝试 JAVA_HOME
    val javaHome = System.getProperty("java.home")
    val jdkPath = file("$javaHome/bin/$binaryName")
    if (jdkPath.exists()) return jdkPath.absolutePath

    // 4. 最后尝试直接调用命令
    return binaryName
}

val generateBindings = tasks.register<Exec>("generateBindings") {
    group = "build"
    description = "使用 jextract 自动从 Rust 头文件生成 Java FFM 绑定。"

    doFirst {
        if (!rustHeaderFile.exists()) {
            throw GradleException("❌ 未找到 Rust 头文件: ${rustHeaderFile.absolutePath}。\n请先编译 Rust 项目 (cargo build --release)。")
        }
        generatedSourceDir.get().asFile.mkdirs()
    }

    commandLine(
        findJextract(),
        "--output", generatedSourceDir.get().asFile.absolutePath,
        "--target-package", targetPackage,
        "--header-class-name", "ecobridge_rust_h", // 关键：固定类名
        "--library", "ecobridge_rust",
        rustHeaderFile.absolutePath
    )

    inputs.file(rustHeaderFile)
    outputs.dir(generatedSourceDir)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

sourceSets {
    main {
        java.srcDir(generatedSourceDir)
    }
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://repo.lanink.cn/repository/maven-public/")
    // [新增] PlaceholderAPI 仓库
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    flatDir { dirs("libs") }
}

dependencies {
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")
    // [新增] PlaceholderAPI 依赖
    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("redis.clients:jedis:7.2.0")
    
    // 🔥 Jackson 高性能 JSON 处理库
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    
    compileOnly("com.google.code.gson:gson:2.12.1")
}

tasks.withType<JavaCompile> {
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
    
    // 🔥 将 Jackson 重新打包到插件内部
    relocate("com.fasterxml.jackson", "$prefix.jackson")
    
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