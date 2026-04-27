import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties
import java.text.SimpleDateFormat
import java.util.Date
import java.io.File

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.ow2.asm:asm-commons:9.9.1")
    }
}

plugins {
    `java-library`
    checkstyle
    id("com.gradleup.shadow") version "9.3.1"
    id("com.diffplug.spotless") version "6.25.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    idea
}

group = "top.ellan"

fun getGitHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "dev" // fallback when git is unavailable
    }
}

fun getBuildTime(): String {
    return SimpleDateFormat("yyyyMMdd").format(Date())
}

version = "1.0.0-${getBuildTime()}-${getGitHash()}"

println("Building EcoBridge version: $version")

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
            val possiblePaths = listOf(
                file("$localHome/bin/$binaryName"), 
                file("$localHome/$binaryName")
            )
            for (path in possiblePaths) if (path.exists()) return path.absolutePath
        }
    }
    return binaryName 
}

    fun executableFound(name: String): Boolean {
        val f = file(name)
        if (f.exists()) return true
        val pathEnv = System.getenv("PATH") ?: return false
        val sep = File.pathSeparator
        val fileSep = File.separator
        pathEnv.split(sep).forEach { entry ->
            val candidate = File(entry + fileSep + name)
            if (candidate.exists()) return true
        }
        return false
    }

val generateBindings = tasks.register<Exec>("generateBindings") {
    group = "build"
    description = "Generate Java FFM bindings from Rust header via jextract."
    doFirst {
        if (!rustHeaderFile.exists()) {
            throw GradleException("Rust header not found: ${rustHeaderFile.absolutePath}")
        }
        generatedSourceDir.get().asFile.mkdirs()
    }
        onlyIf { executableFound(findJextract()) }
    isIgnoreExitValue = true
    commandLine(
        findJextract(),
        "--output", generatedSourceDir.get().asFile.absolutePath,
        "--target-package", targetPackage,
        "--header-class-name", "ecobridge_rust_h",
        rustHeaderFile.absolutePath
    )
    doLast {
        println("jextract unavailable: bindings generation skipped (set JEXTRACT_HOME to enable).")
        generatedSourceDir.get().asFile.mkdirs()
    }
    inputs.file(rustHeaderFile)
    outputs.dir(generatedSourceDir)
}

    val prepareGeneratedDir = tasks.register("prepareGeneratedDir") {
        doLast { generatedSourceDir.get().asFile.mkdirs() }
    }

val generateFallbackBindings = tasks.register("generateFallbackBindings") {
    group = "build"
    description = "Generate fallback Panama layout bindings when jextract is unavailable."
    doLast {
        val genRoot = generatedSourceDir.get().asFile
        val packageDir = File(genRoot, targetPackage.replace('.', '/'))
        packageDir.mkdirs()

        val existingJava = packageDir.listFiles()?.any { it.extension == "java" } == true
        if (existingJava) {
            println("Existing generated bindings detected, skip fallback generation.")
            return@doLast
        }

        fun writeClass(name: String, body: String) {
            File(packageDir, "$name.java").writeText(
                """
                package $targetPackage;

                import java.lang.foreign.MemoryLayout;
                import static java.lang.foreign.ValueLayout.*;

                public final class $name {
                    private $name() {}

                    public static MemoryLayout layout() {
                        return $body;
                    }
                }
                """.trimIndent() + System.lineSeparator(),
                Charsets.UTF_8
            )
        }

        writeClass("TradeContext", """
MemoryLayout.structLayout(
        JAVA_LONG.withName("base_price_micros"),
        JAVA_LONG.withName("current_amount"),
        JAVA_DOUBLE.withName("inflation_rate"),
        JAVA_LONG.withName("current_timestamp"),
        JAVA_LONG.withName("play_time_seconds"),
        JAVA_INT.withName("timezone_offset"),
        JAVA_INT.withName("newbie_mask"),
        JAVA_DOUBLE.withName("market_heat"),
        JAVA_DOUBLE.withName("eco_saturation")
)
        """.trimIndent())

        writeClass("MarketConfig", """
MemoryLayout.structLayout(
        JAVA_DOUBLE.withName("base_lambda"),
        JAVA_DOUBLE.withName("volatility_factor"),
        JAVA_DOUBLE.withName("seasonal_amplitude"),
        JAVA_DOUBLE.withName("weekend_multiplier"),
        JAVA_DOUBLE.withName("newbie_protection_rate"),
        JAVA_DOUBLE.withName("seasonal_weight"),
        JAVA_DOUBLE.withName("weekend_weight"),
        JAVA_DOUBLE.withName("newbie_weight"),
        JAVA_DOUBLE.withName("inflation_weight")
)
        """.trimIndent())

        writeClass("TransferResult", """
MemoryLayout.structLayout(
        JAVA_LONG.withName("final_tax_micros"),
        JAVA_INT.withName("is_blocked"),
        JAVA_INT.withName("warning_code")
)
        """.trimIndent())

        writeClass("TransferContext", """
MemoryLayout.structLayout(
        JAVA_LONG.withName("amount_micros"),
        JAVA_LONG.withName("sender_balance"),
        JAVA_LONG.withName("receiver_balance"),
        JAVA_DOUBLE.withName("inflation_rate"),
        JAVA_LONG.withName("item_base_limit"),
        JAVA_DOUBLE.withName("item_growth_rate"),
        JAVA_LONG.withName("item_max_limit"),
        JAVA_LONG.withName("sender_play_time"),
        JAVA_LONG.withName("receiver_play_time"),
        JAVA_DOUBLE.withName("sender_activity_score"),
        JAVA_DOUBLE.withName("sender_velocity"),
        JAVA_LONG.withName("_padding")
)
        """.trimIndent())

        writeClass("RegulatorConfig", """
MemoryLayout.structLayout(
        JAVA_DOUBLE.withName("base_tax_rate"),
        JAVA_LONG.withName("luxury_threshold"),
        JAVA_DOUBLE.withName("luxury_tax_rate"),
        JAVA_DOUBLE.withName("wealth_gap_tax_rate"),
        JAVA_LONG.withName("poor_threshold"),
        JAVA_LONG.withName("rich_threshold"),
        JAVA_DOUBLE.withName("_reserved"),
        JAVA_DOUBLE.withName("warning_ratio"),
        JAVA_LONG.withName("warning_min_amount"),
        JAVA_DOUBLE.withName("newbie_hours"),
        JAVA_DOUBLE.withName("veteran_hours"),
        JAVA_DOUBLE.withName("velocity_threshold")
)
        """.trimIndent())

        writeClass("PidState", """
MemoryLayout.structLayout(
        JAVA_DOUBLE.withName("kp"),
        JAVA_DOUBLE.withName("ki"),
        JAVA_DOUBLE.withName("kd"),
        JAVA_DOUBLE.withName("lambda"),
        JAVA_DOUBLE.withName("integral"),
        JAVA_DOUBLE.withName("prev_pv"),
        JAVA_DOUBLE.withName("filtered_d"),
        JAVA_DOUBLE.withName("integration_limit"),
        JAVA_INT.withName("is_saturated"),
        JAVA_INT.withName("_padding")
)
        """.trimIndent())

        println("Fallback bindings generated at: ${packageDir.absolutePath}")
    }
    outputs.dir(generatedSourceDir)
}

idea {
    module {
        generatedSourceDirs.add(generatedSourceDir.get().asFile)
    }
}

java {
    toolchain { 
        languageVersion.set(JavaLanguageVersion.of(25)) 
    }
}

sourceSets {
    main {
        java.srcDir(generatedSourceDir)
    }
}

sourceSets {
    main {
        java {
            exclude("top/ellan/ecobridge/test/**")
        }
    }
}

repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://oss.sonatype.org/content/repositories/releases/")
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://repo.lanink.cn/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.momirealms.net/releases/")
    
    flatDir { dirs("libs") }
}

checkstyle {
    toolVersion = "10.18.2"
    configFile = file("$rootDir/config/checkstyle/checkstyle.xml")
    isShowViolations = true
}

spotless {
    java {
        target(
            "src/main/java/top/ellan/ecobridge/integration/platform/**/*.java",
            "src/main/java/top/ellan/ecobridge/application/bootstrap/**/*.java",
            "src/test/java/top/ellan/ecobridge/test/**/*.java"
        )
        googleJavaFormat("1.28.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
    config.setFrom(file("$rootDir/config/detekt/detekt.yml"))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")
    
    compileOnly("net.momirealms:craft-engine-core:0.0.66") 
    compileOnly("net.momirealms:craft-engine-bukkit:0.0.66")

    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))

    compileOnly(platform("tools.jackson:jackson-bom:3.0.0"))
    compileOnly("tools.jackson.core:jackson-databind")

    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    compileOnly("com.zaxxer:HikariCP:7.0.2")
    compileOnly("com.github.ben-manes.caffeine:caffeine:3.2.3")
    compileOnly("redis.clients:jedis:5.2.0")

    compileOnly("org.ow2.asm:asm:9.9.1")
    compileOnly("org.ow2.asm:asm-commons:9.9.1")
    
    compileOnly("net.bytebuddy:byte-buddy-agent:1.15.10")

    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    testImplementation("org.ow2.asm:asm:9.9.1")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    dependsOn(generateBindings, prepareGeneratedDir, generateFallbackBindings)
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf(
        "-Xlint:unchecked",
        "-Xlint:-preview"
    ))
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    
    manifest {
        attributes(
            "Premain-Class" to "top.ellan.ecobridge.EcoBridge",
            "Agent-Class" to "top.ellan.ecobridge.EcoBridge",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }

    from("src/main/resources") {
        include("*.dll", "*.so", "*.dylib", "natives/**")
    }
    
    mergeServiceFiles()
}

tasks.withType<ProcessResources> {
    val props = mapOf("version" to project.version)
    inputs.properties(props)

    filteringCharset = "UTF-8"

    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn("spotlessCheck", "checkstyleMain", "checkstyleTest", "detekt")
}
