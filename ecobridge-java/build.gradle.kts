import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // ASM 9.9.1: 支持 Java 25 字节码，确保 shadowJar 正常工作
        classpath("org.ow2.asm:asm-commons:9.9.1")
    }
}

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.3.1"
    idea
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
    
    val envHome = System.getenv("JEXTRACT_HOME")
    if (!envHome.isNullOrBlank()) {
        val path = file("$envHome/bin/$binaryName")
        if (path.exists()) return path.absolutePath
    }

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
    return binaryName 
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
        rustHeaderFile.absolutePath
    )
    inputs.file(rustHeaderFile)
    outputs.dir(generatedSourceDir)
}

idea {
    module {
        generatedSourceDirs.add(generatedSourceDir.get().asFile)
    }
}

// --- [Java 环境与工具链] ---
java {
    toolchain { 
        languageVersion.set(JavaLanguageVersion.of(25)) 
    }
}

sourceSets {
    main {
        java.srcDir(generateBindings)
    }
}

// --- [仓库配置] ---
repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://oss.sonatype.org/content/repositories/releases/")
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://repo.lanink.cn/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    flatDir { dirs("libs") }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))

    implementation(platform("tools.jackson:jackson-bom:3.0.0"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("redis.clients:jedis:5.2.0")
    
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
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

// --- [ShadowJar 配置 - 核心修复区域] ---
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    val prefix = "top.ellan.ecobridge.libs"
    
    // 重定向配置
    relocate("tools.jackson", "$prefix.jackson")
    relocate("com.fasterxml.jackson.annotation", "$prefix.jackson.annotations")
    relocate("com.zaxxer.hikari", "$prefix.hikari")
    relocate("org.mariadb.jdbc", "$prefix.mariadb")
    // 注意：报错信息显示之前重定向到了 $prefix.caffeine，这里建议暂时保持关闭重定向
    // 以确保排查 minimize 问题，如果确实需要重定向，请取消下面这行的注释
    // relocate("com.github.benmanes.caffeine", "$prefix.caffeine")
    relocate("redis.clients", "$prefix.jedis")
    
    from("src/main/resources") {
        include("*.dll", "*.so", "*.dylib", "natives/**")
    }
    
    mergeServiceFiles()
    
    minimize {
        // 排除 MariaDB
        exclude(dependency("org.mariadb.jdbc:.*"))
        
        // ✅ 核心修复：排除 Caffeine
        // 防止 Shadow 认为 Caffeine 内部通过反射加载的实现类（如 SSMSW）是无用的并将其剔除
        exclude(dependency("com.github.ben-manes.caffeine:caffeine:.*"))
    }
}

tasks.withType<ProcessResources> {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}