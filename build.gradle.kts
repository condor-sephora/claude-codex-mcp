import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "io.anthropic.codex"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

// ---------- Integration-test source set ----------
sourceSets {
    create("integrationTest") {
        kotlin.srcDir("src/integrationTest/kotlin")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += output + compileClasspath
    }
}

configurations {
    named("integrationTestImplementation") {
        extendsFrom(configurations.testImplementation.get())
    }
    named("integrationTestRuntimeOnly") {
        extendsFrom(configurations.testRuntimeOnly.get())
    }
}

// ---------- Dependencies ----------
dependencies {
    // MCP Kotlin SDK (official, maintained with JetBrains)
    implementation("io.modelcontextprotocol:kotlin-sdk:0.8.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // JSON serialization for structured results
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // SLF4J simple — configured to write to stderr via simplelogger.properties
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // ---------- Unit tests ----------
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ---------- Integration tests ----------
    "integrationTestImplementation"(kotlin("test"))
    "integrationTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.3")
    "integrationTestImplementation"("org.junit.jupiter:junit-jupiter-params:5.10.3")
    "integrationTestImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

// ---------- Application entry point ----------
application {
    mainClass.set("MainKt")
}

// ---------- Kotlin compile options ----------
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
    }
}

// ---------- Unit test task ----------
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// ---------- Shadow JAR (fat JAR) ----------
val shadowJarTask = tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("claude-codex-mcp")
    archiveClassifier.set("all")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
    // Merge SLF4J service files so the provider is discovered inside the fat JAR
    mergeServiceFiles()
    // Exclude duplicate license files
    exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
}

tasks.build {
    dependsOn(shadowJarTask)
}

// ---------- Make fake-codex.sh executable ----------
val makeTestFixturesExecutable by tasks.registering {
    doLast {
        val script = file("test-fixtures/fake-codex/fake-codex.sh")
        if (script.exists()) {
            script.setExecutable(true)
        }
    }
}

// ---------- Integration test task ----------
val integrationTest by tasks.registering(Test::class) {
    description = "Runs deterministic MCP integration/e2e tests using fake Codex."
    group = "verification"

    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath

    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }

    // Integration tests need the fat JAR to launch the server as a subprocess
    dependsOn(shadowJarTask)
    dependsOn(makeTestFixturesExecutable)

    // Pass paths to the fat JAR and fake Codex script via system properties
    doFirst {
        val jarFile = shadowJarTask.get().archiveFile.get().asFile
        systemProperty("mcp.jar.path", jarFile.absolutePath)
        systemProperty("fake.codex.path", file("test-fixtures/fake-codex/fake-codex.sh").absolutePath)
        systemProperty("project.dir", projectDir.absolutePath)
    }
}

// ---------- Optional real Codex smoke test ----------
val realCodexSmokeTest by tasks.registering(Test::class) {
    description = "Optional smoke test against real Codex CLI. Set CODEX_MCP_RUN_REAL_CODEX_TESTS=true to enable."
    group = "verification"

    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath

    useJUnitPlatform()
    include("**/RealCodexSmokeTest*")

    // Disabled by default; only runs when explicitly enabled
    onlyIf { System.getenv("CODEX_MCP_RUN_REAL_CODEX_TESTS") == "true" }

    dependsOn(shadowJarTask)

    doFirst {
        val jarFile = shadowJarTask.get().archiveFile.get().asFile
        systemProperty("mcp.jar.path", jarFile.absolutePath)
    }
}
