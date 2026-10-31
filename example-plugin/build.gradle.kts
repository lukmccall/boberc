plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.gradle.java.test.fixtures)
    alias(libs.plugins.gradle.idea)
}

/*
 * A compiler plugin written with boberc. Its main source set is compiled *with the boberc compiler plugin*,
 * which turns `@Template` functions into template models embedded in this plugin's binary.
 */

val testDataDir = layout.projectDirectory.dir("testData")
val testGenDirectory = layout.buildDirectory.dir("test-gen")

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    testFixtures {
        java.setSrcDirs(listOf("test-fixtures"))
    }
    test {
        java.setSrcDirs(listOf("test", testGenDirectory))
        resources.setSrcDirs(listOf(testDataDir))
    }
}

idea {
    module.generatedSourceDirs.add(testGenDirectory.get().asFile)
}

val testArtifacts: Configuration = configurations.create("testArtifact")

dependencies {
    // Apply boberc to the compilation of this plugin (the Gradle plugin in :gradle-plugin does the same).
    kotlinCompilerPluginClasspathMain(project(":compiler-plugin"))

    compileOnly(libs.kotlin.compiler)
    // In a published plugin this would be shaded into the plugin jar.
    implementation(project(":boberc-runtime"))

    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.kotlin.test.framework)
    testFixturesApi(libs.kotlin.compiler)
    testFixturesRuntimeOnly(libs.junit)

    testArtifacts(libs.kotlin.stdlib)
    testArtifacts(libs.kotlin.stdlib.jdk8)
    testArtifacts(libs.kotlin.reflect)
    testArtifacts(libs.kotlin.test)
    testArtifacts(libs.kotlin.script.runtime)
    testArtifacts(libs.kotlin.annotations.jvm)
}

tasks.test {
    // ./gradlew test -PupdateTestData rewrites expected test data from the actual results.
    if (project.hasProperty("updateTestData")) systemProperty("kotlin.test.update.test.data", "true")
    dependsOn(testArtifacts)

    useJUnitPlatform()
    workingDir = rootDir

    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")

    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", rootDir)
}

kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

val generateTests = tasks.register<JavaExec>("generateTests") {
    inputs.dir(testDataDir)
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(testGenDirectory)
        .withPropertyName("generatedTests")

    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainClass.set("boberc.example.GenerateTestsKt")
    workingDir = rootDir
    args(
        listOf(
            testGenDirectory.get().asFile.absolutePath,
            testDataDir.asFile.absolutePath,
        )
    )
}

tasks.compileTestKotlin {
    dependsOn(generateTests)
}

fun Test.setLibraryProperty(propName: String, jarName: String) {
    val path = testArtifacts.files
        .find { """$jarName-\d.*""".toRegex().matches(it.name) }
        ?.absolutePath
        ?: return
    systemProperty(propName, path)
}
