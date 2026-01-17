import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // ASM 9.9.1: 完美支持 Java 25 字节码
        classpath("org.ow2.asm:asm-commons:9.9.1")
    }
}

plugins {
    `java-library`
    // 严格遵照您的要求：使用 Shadow 9.3.1
    id("com.gradleup.shadow") version "9.3.1"
}

group = "top.ellan"
version = "1.0-SNAPSHOT"

// --- [jextract 自动化配置] ---
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
            val possiblePaths = listOf(file("$localHome/bin/$binaryName"), file("$localHome/$binaryName"))
            for (path in possiblePaths) if (path.exists()) return path.absolutePath
        }
    }
    val envHome = System.getenv("JEXTRACT_HOME")
    if (envHome != null) {
        val path = file("$envHome/bin/$binaryName")
        if (path.exists()) return path.absolutePath
    }
    val javaHome = System.getProperty("java.home")
    val jdkPath = file("$javaHome/bin/$binaryName")
    if (jdkPath.exists()) return jdkPath.absolutePath
    return binaryName
}

val generateBindings = tasks.register<Exec>("generateBindings") {
    group = "build"
    description = "Generate Java FFM bindings from Rust header."
    doFirst {
        if (!rustHeaderFile.exists()) {
            println("❌ Error: Rust header not found: ${rustHeaderFile.absolutePath}")
            throw GradleException("Rust header missing. Please check build-rust stage.")
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
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://repo.lanink.cn/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    flatDir { dirs("libs") }
}

dependencies {
    // ⚠️ 严格保留：Paper 1.21.11-R0.1-SNAPSHOT
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    
    // PlaceholderAPI
    compileOnly("me.clip:placeholderapi:2.11.6")

    // 其他插件依赖
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")
    
    // 🔥 高性能组件库 (全面升级至 2026 最新版)
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("redis.clients:jedis:5.2.0")
    
    // 🔥 Jackson (2.20.1)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("com.fasterxml.jackson.core:jackson-core:2.20.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.20.1")
    
    // Gson (升级至 2.13.2)
    compileOnly("com.google.code.gson:gson:2.13.2")

    // JUnit 5 (升级至 5.14.1)
    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
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
    
    // 依赖重定位防止冲突
    relocate("com.zaxxer.hikari", "$prefix.hikari")
    relocate("org.mariadb.jdbc", "$prefix.mariadb")
    relocate("com.github.benmanes.caffeine", "$prefix.caffeine")
    relocate("redis.clients", "$prefix.jedis")
    relocate("com.fasterxml.jackson", "$prefix.jackson")
    
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
