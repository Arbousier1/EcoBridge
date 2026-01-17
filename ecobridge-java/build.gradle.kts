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
    id("com.gradleup.shadow") version "9.3.1"
}

group = "top.ellan"
version = "1.0-SNAPSHOT"

// ==================================================
// jextract 配置
// ==================================================
val rustHeaderFile = file("${projectDir}/../ecobridge-rust/ecobridge_rust.h")
val generatedSourceDir = layout.buildDirectory.dir("generated/sources/jextract")
val targetPackage = "top.ellan.ecobridge.gen"

fun findJextract(): String {
    val os = org.gradle.internal.os.OperatingSystem.current()
    val binaryName = if (os.isWindows) "jextract.bat" else "jextract"

    // 1. local.properties
    val localPropsFile = file("local.properties")
    if (localPropsFile.exists()) {
        val props = Properties()
        localPropsFile.inputStream().use { props.load(it) }
        props.getProperty("jextract.home")?.let { home ->
            listOf(
                file("$home/bin/$binaryName"),
                file("$home/$binaryName")
            ).firstOrNull { it.exists() }?.let {
                println("✅ [Local] 使用 jextract: ${it.absolutePath}")
                return it.absolutePath
            }
        }
    }

    // 2. CI 环境变量
    System.getenv("JEXTRACT_HOME")?.let { home ->
        val path = file("$home/bin/$binaryName")
        if (path.exists()) {
            println("✅ [CI] 使用 jextract: ${path.absolutePath}")
            return path.absolutePath
        }
    }

    // 3. JAVA_HOME
    val javaHome = System.getProperty("java.home")
    val jdkPath = file("$javaHome/bin/$binaryName")
    if (jdkPath.exists()) return jdkPath.absolutePath

    // 4. fallback
    return binaryName
}

val generateBindings = tasks.register<Exec>("generateBindings") {
    group = "build"
    description = "使用 jextract 从 Rust 头文件生成 Java FFM 绑定"

    doFirst {
        if (!rustHeaderFile.exists()) {
            throw GradleException(
                "❌ 未找到 Rust 头文件: ${rustHeaderFile.absolutePath}\n" +
                "请先执行 cargo build --release"
            )
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

// ==================================================
// Java / Toolchain
// ==================================================
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// ==================================================
// 🔥 关键修复点（必须这样）
// ==================================================
sourceSets {
    main {
        java {
            // 让 Gradle 知道：这个源码目录是 generateBindings 产出的
            srcDir(generatedSourceDir).builtBy(generateBindings)
        }
    }
}

// ==================================================
// Repositories
// ==================================================
repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://repo.lanink.cn/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    flatDir { dirs("libs") }
}

// ==================================================
// Dependencies
// ==================================================
dependencies {
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))

    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")
    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("redis.clients:jedis:7.2.0")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    compileOnly("com.google.code.gson:gson:2.12.1")
}

// ==================================================
// Compile（稳定配置）
// ==================================================
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:unchecked")
}

// ==================================================
// ShadowJar
// ==================================================
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")

    val prefix = "top.ellan.ecobridge.libs"
    relocate("com.zaxxer.hikari", "$prefix.hikari")
    relocate("org.mariadb.jdbc", "$prefix.mariadb")
    relocate("com.github.benmanes.caffeine", "$prefix.caffeine")
    relocate("redis.clients", "$prefix.jedis")
    relocate("com.fasterxml.jackson", "$prefix.jackson")

    from("src/main/resources") {
        include("*.dll", "*.so", "*.dylib")
    }

    mergeServiceFiles()
}

// ==================================================
// Resources
// ==================================================
tasks.withType<ProcessResources> {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
