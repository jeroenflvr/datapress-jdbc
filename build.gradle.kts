import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    `maven-publish`
    id("com.diffplug.spotless") version "6.25.0"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "org.datap-rs"
version = "0.1.0-SNAPSHOT"
description = "Read-only Type-4 JDBC driver for DataPress"

repositories {
    mavenCentral()
}

// -----------------------------------------------------------------------------
// Dependency versions (single source of truth)
// -----------------------------------------------------------------------------
val arrowVersion = "17.0.0"
val jacksonVersion = "2.17.2"
val junitVersion = "5.10.3"
val assertjVersion = "3.26.3"

dependencies {
    // Arrow IPC decoding. arrow-memory-unsafe is chosen over arrow-memory-netty to
    // avoid relocating Netty and its native libs (see SKILL.md → Packaging).
    api("org.apache.arrow:arrow-vector:$arrowVersion")
    runtimeOnly("org.apache.arrow:arrow-memory-unsafe:$arrowVersion")

    // Jackson only for metadata / error JSON.
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.apache.arrow:arrow-memory-unsafe:$arrowVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// -----------------------------------------------------------------------------
// Java: bytecode target 11, compiled with whatever JDK runs Gradle via --release.
// CI runs the test matrix on JDK 11 / 17 / 21.
// -----------------------------------------------------------------------------
java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all,-processing")
}

// Arrow memory needs this on JDK 16+; documented in README for tool users.
val arrowJvmArgs = listOf("--add-opens=java.base/java.nio=ALL-UNNAMED")

tasks.test {
    useJUnitPlatform()
    jvmArgs(arrowJvmArgs)
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// -----------------------------------------------------------------------------
// Integration test source set (gated on DATAPRESS_URL at run time; see SKILL.md).
// -----------------------------------------------------------------------------
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

val integrationTestTask =
    tasks.register<Test>("integrationTest") {
        description = "Runs integration tests against a live DataPress server (needs DATAPRESS_URL)."
        group = "verification"
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        useJUnitPlatform()
        jvmArgs(arrowJvmArgs)
        shouldRunAfter(tasks.test)
    }

// -----------------------------------------------------------------------------
// Generate VersionInfo resource from the Gradle project version (single source).
// -----------------------------------------------------------------------------
val generateVersionInfo =
    tasks.register("generateVersionInfo") {
        val outputDir = layout.buildDirectory.dir("generated/version")
        val versionValue = project.version.toString()
        inputs.property("version", versionValue)
        outputs.dir(outputDir)
        doLast {
            val dir = outputDir.get().asFile.resolve("org/datapress/jdbc/internal/util")
            dir.mkdirs()
            dir.resolve("datapress-jdbc-version.properties").writeText(
                "version=$versionValue\n",
            )
        }
    }

sourceSets.main {
    resources.srcDir(generateVersionInfo.map { it.outputs.files.singleFile })
}

// -----------------------------------------------------------------------------
// Spotless / google-java-format
// -----------------------------------------------------------------------------
spotless {
    java {
        googleJavaFormat("1.22.0")
        target("src/**/*.java")
        importOrder()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

// -----------------------------------------------------------------------------
// Shaded (fat) jar — the published artifact.
// -----------------------------------------------------------------------------
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    val shadeBase = "org.datapress.jdbc.internal.shaded"
    relocate("org.apache.arrow", "$shadeBase.org.apache.arrow")
    relocate("com.fasterxml.jackson", "$shadeBase.com.fasterxml.jackson")
    relocate("com.google.flatbuffers", "$shadeBase.com.google.flatbuffers")

    // Preserve the ServiceLoader registration; merge service files.
    mergeServiceFiles()

    manifest {
        attributes(
            "Automatic-Module-Name" to "org.datapress.jdbc",
            "Implementation-Title" to "datapress-jdbc",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

// The thin jar keeps a classifier; the shaded jar is the default artifact.
tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")
}

tasks.build {
    dependsOn(tasks.named("shadowJar"))
}

// -----------------------------------------------------------------------------
// Publishing config prepared but publishing left manual (unsigned for now).
// -----------------------------------------------------------------------------
publishing {
    publications {
        create<MavenPublication>("shaded") {
            artifact(tasks.named("shadowJar"))
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))
            pom {
                name.set("datapress-jdbc")
                description.set(project.description)
                url.set("https://github.com/jeroenflvr/datapress-jdbc")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
