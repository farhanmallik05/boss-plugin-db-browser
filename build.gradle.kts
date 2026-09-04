import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.0.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../Boss open source/plugin-platform/plugin-api-core"

// Minimum boss-plugin-api version — updated to match the local built JAR
val bossPluginApiVersion = "1.0.87"

val bossPluginApiJar =
    if (useLocalDependencies) {
        files("$bossPluginApiPath/build/api-contract/boss-plugin-api-$bossPluginApiVersion.jar")
    } else {
        files("build/downloaded-deps/boss-plugin-api.jar")
    }

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    compileOnly(bossPluginApiJar)

    // Compose dependencies (host-provided at runtime)
    compileOnly(compose.desktop.currentOs)
    compileOnly(compose.runtime)
    compileOnly(compose.ui)
    compileOnly(compose.foundation)
    compileOnly(compose.material)
    compileOnly(compose.materialIconsExtended)

    // Decompose for ComponentContext
    compileOnly("com.arkivanov.decompose:decompose:3.3.0")
    compileOnly("com.arkivanov.essenty:lifecycle:2.5.0")
    testImplementation("com.arkivanov.decompose:decompose:3.3.0")
    testImplementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Serialization
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Test classpath
    testImplementation(bossPluginApiJar)
    testImplementation(kotlin("test"))
    testImplementation(compose.runtime)
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
    testImplementation(compose.desktop.uiTestJUnit4)
    // SQLite driver: used only in tests (in-memory DB for tool integration tests).
    // The plugin itself does NOT bundle JDBC drivers — real plugins do; see README.
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Coroutines test utilities (runBlocking is in core, but the test artifact adds TestScope etc.)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}

// Task to build plugin JAR with compiled classes and resources only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-db-browser-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS DB Browser Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.dbbrowser.DbBrowserDynamicPlugin"
        )
    }

    from(sourceSets.main.get().output)
    from("src/main/resources")
}

// Sync version from build.gradle.kts into plugin manifests (single source of truth)
tasks.processResources {
    inputs.property("pluginVersion", version)
    inputs.property("bossPluginApiVersion", bossPluginApiVersion)
    filesMatching("**/plugin.json") {
        filter { line ->
            line
                .replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$version"""")
                .replace(Regex(""""apiVersion"\s*:\s*"[^"]*""""), """"apiVersion": "$bossPluginApiVersion"""")
        }
    }
    filesMatching("**/plugin.manifest.json") {
        filter { line ->
            line
                .replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$version"""")
                .replace(Regex(""""apiVersion"\s*:\s*"[^"]*""""), """"apiVersion": "$bossPluginApiVersion"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}
