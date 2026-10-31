plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.gradle.plugin)
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    test {
        java.setSrcDirs(listOf("test"))
        resources.setSrcDirs(listOf("testResources"))
    }
}

dependencies {
    implementation(libs.kotlin.gradle.plugin.api)
    testImplementation(libs.kotlin.test.junit5)
}

buildConfig {
    packageName("boberc.gradle")

    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"boberc\"")

    val pluginProject = project(":compiler-plugin")
    buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${pluginProject.group}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${pluginProject.name}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${pluginProject.version}\"")

    val runtimeProject = project(":boberc-runtime")
    buildConfigField(
        type = "String",
        name = "RUNTIME_LIBRARY_COORDINATES",
        expression = "\"${runtimeProject.group}:${runtimeProject.name}:${runtimeProject.version}\""
    )
}

gradlePlugin {
    plugins {
        create("Boberc") {
            id = "boberc"
            displayName = "boberc"
            description = "Write Kotlin compiler plugins with plain Kotlin templates"
            implementationClass = "boberc.gradle.BobercGradlePlugin"
        }
    }
}
