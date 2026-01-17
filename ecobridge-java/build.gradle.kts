import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // ASM 9.9.1: 支持 Java 25 字节码
        classpath("org.ow2.asm:asm-commons:9.9.1")
    }
}

plugins {
    `java-library`
    // 严格保留要求的版本
    id("com.gradleup.shadow") version "9.3.1"
}

group = "top.ellan"
version = "1.0-SNAPSHOT"

// --- [jextract 自动绑定逻辑] ---
val rustHeaderFile = file("${projectDir}/../ecobridge-rust/ecobridge_rust.h")
val generatedSourceDir = layout.buildDirectory.dir("generated/sources/jextract")
val targetPackage = "top.ellan.ecobridge.gen"

fun findJextract(): String {
    val os = org.gradle.internal.os.OperatingSystem.current()
    val binaryName = if (os.isWindows) "jextract.bat" else "jextract"
    val localPropsFile = file("local.properties")
    if (localPropsFile.exists()) {
        val props = Properties()
        localPropsFile.inputStream().use { props.load(it) }
        val localHome = props.getProperty("jextract.home")
        if (localHome != null) {
            val path = file("$localHome/bin/$binaryName")
            if (path.exists()) return path.absolutePath
        }
    }
    val envHome = System.getenv("JEXTRACT_HOME")
    if (envHome != null) {
        val path = file("$envHome/bin/$binaryName")
        if (path.exists()) return path.absolutePath
    }
    return binaryName
}

val generateBindings = tasks.register<Exec>("generateBindings") {
    group = "build"
    doFirst {
        if (!rustHeaderFile.exists()) throw GradleException("Rust header not found at ${rustHeaderFile.absolutePath}")
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

// --- [Java 环境配置] ---
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

sourceSets {
    main {
        java.srcDir(generateBindings)
    }
}

repositories {
    mavenCentral()
    // 🔥 关键修复：根据 Wiki 提示，添加 Sonatype 官方发布和快照仓库
    // Jackson 3.0 的部分组件（如 2.20.0 版本的 annotations）在此处发布
    maven("https://oss.sonatype.org/content/repositories/releases/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://repo.lanink.cn/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    flatDir { dirs("libs") }
}

dependencies {
    // 严格保留：Paper 1.21.11
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))

    // 🔥 Jackson 3.0 迁移适配
    // 使用新的 Group ID: tools.jackson
    implementation(platform("tools.jackson:jackson-bom:3.0.0"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.core:jackson-core")
    // Wiki 规定：jackson-annotations 保持 com.fasterxml.jackson.core 坐标
    // 此处不指定版本，交由 jackson-bom 自动解析（解析为 Wiki 提到的 2.20 系列）
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    // 数据库与缓存 (2026年1月最新稳定版)
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("redis.clients:jedis:7.2.0")
    
    compileOnly("com.google.code.gson:gson:2.13.2")

    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<JavaCompile> {
    dependsOn(generateBindings)
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("--enable-preview", "-Xlint:unchecked", "-Xlint:-preview"))
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    val prefix = "top.ellan.ecobridge.libs"
    
    // 🔥 更新 Shadow 重定位逻辑以匹配 Jackson 3 的新包名
    relocate("tools.jackson", "$prefix.jackson")
    // 特别处理 annotations 包名，它在 3.0 中依然保持 com.fasterxml.jackson.annotation
    relocate("com.fasterxml.jackson.annotation", "$prefix.jackson.annotations")
    
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
    filesMatching("plugin.yml") { expand(props) }
}
