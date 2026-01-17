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
    // 2026年 1月最新稳定版 Shadow 插件 (com.gradleup.shadow)
    id("com.gradleup.shadow") version "8.3.6"
}

group = "top.ellan"
version = "1.0-SNAPSHOT"

// --- [jextract 自动化配置逻辑] ---
// 确保路径指向 artifact 下载后的位置
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

    // 确保目录存在
    doFirst {
        if (!rustHeaderFile.exists()) {
            // 在 CI 环境下，打印当前目录结构帮助调试
            println("❌ 错误：未找到头文件: ${rustHeaderFile.absolutePath}")
            println("当前目录文件列表:")
            projectDir.parentFile.listFiles()?.forEach { println(" - ${it.name}") }
            throw GradleException("Rust 头文件缺失，请检查 build-rust 阶段是否成功上传了 artifact。")
        }
        generatedSourceDir.get().asFile.mkdirs()
    }

    commandLine(
        findJextract(),
        "--output", generatedSourceDir.get().asFile.absolutePath,
        "--target-package", targetPackage,
        // 🔥 强制指定 header class name，确保 Java 代码能引用到 ecobridge_rust_h
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
        // ✅ 关键修复：将任务输出注册为源码目录
        // 这会自动建立 compileJava -> generateBindings 的依赖关系
        // 从而解决 "package top.ellan.ecobridge.gen does not exist"
        java.srcDir(generateBindings)
    }
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://repo.lanink.cn/repository/maven-public/")
    // ✅ 关键修复：新增 PlaceholderAPI 仓库
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    flatDir { dirs("libs") }
}

dependencies {
    // Spigot/Paper API
    // ⚠️ 已保留您指定的 1.21.1 版本
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    
    // ✅ 关键修复：新增 PlaceholderAPI 依赖 (解决 Hook 报错)
    compileOnly("me.clip:placeholderapi:2.11.6")

    // 其他插件依赖
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")
    
    // 数据库与工具库 (已保留您指定的版本)
    implementation("org.mariadb.jdbc:mariadb-java-client:3.3.2")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("redis.clients:jedis:5.1.0")
    
    // ✅ 关键修复：新增 Jackson 依赖 (解决 RedisManager 报错)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-core:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.16.1")
    
    compileOnly("com.google.code.gson:gson:2.10.1")

    // 测试依赖
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    // 🔒 双重保险：强制编译任务依赖于绑定生成
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
    
    // 重定位依赖，防止冲突
    relocate("com.zaxxer.hikari", "$prefix.hikari")
    relocate("org.mariadb.jdbc", "$prefix.mariadb")
    relocate("com.github.benmanes.caffeine", "$prefix.caffeine")
    relocate("redis.clients", "$prefix.jedis")
    relocate("com.fasterxml.jackson", "$prefix.jackson") // ✅ 重定位 Jackson 防止冲突
    
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
