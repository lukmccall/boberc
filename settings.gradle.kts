pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

rootProject.name = "boberc"

// Library used by plugin authors: template API, template model, FIR/IR materializers.
include("boberc-runtime")
// The boberc compiler plugin itself: turns `@Template` Kotlin code into template models.
include("compiler-plugin")
// A sample compiler plugin written with boberc (end-to-end proof of the approach).
include("example-plugin")
// Gradle plugin applying boberc to a plugin author's project.
include("gradle-plugin")
