plugins {
    application
}

group = "generalannotator"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Test-only: cross-checks target signatures against JavaParser as used by Specimin (same version).
    testImplementation("com.github.javaparser:javaparser-core:3.28.2")
}

application {
    mainClass = "generalannotator.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Real-tool integration tests (local NullAway checkout, git, javac). Kept out of `test` and
// `check`; run explicitly with `./gradlew integrationTest` and GA_CHECKER_HOME set.
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    // Main classes plus the unit-test fixtures (for example VisualizationFixtures).
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
configurations[integrationTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("integrationTest") {
    description = "Runs real-tool integration tests (requires GA_CHECKER_HOME)."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    systemProperty(
        "ga.fixtures",
        layout.projectDirectory.dir("src/integrationTest/fixtures").asFile.absolutePath,
    )
    inputs.dir("src/integrationTest/fixtures")
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// The Python inference worker's unit tests (fake vllm/nvidia-smi, no GPU or network). Kept out of
// `check` because they need a local Python 3.10+; GA_PYTHON selects the interpreter (default python3).
tasks.register<Exec>("workerTest") {
    description = "Runs the Python inference worker's unit tests (requires Python 3.10+)."
    group = "verification"
    val python = System.getenv("GA_PYTHON") ?: "python3"
    commandLine(
        python, "-c",
        "import sys; assert sys.version_info >= (3, 10), 'the worker tests need Python 3.10+, not ' + sys.version; " +
            "import unittest; r = unittest.main(module=None, argv=['workerTest', 'discover', '-s', " +
            "'src/test/python', '-p', 'test_*.py', '-v'], exit=False).result; " +
            "sys.exit(0 if r.wasSuccessful() and r.testsRun > 0 else 1)",
    )
    inputs.dir("src/test/python")
    inputs.file("src/main/resources/generalannotator/inference/ga_inference_worker.py")
    outputs.upToDateWhen { false }
}
