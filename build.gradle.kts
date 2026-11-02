import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.buildconfig) apply false
}

allprojects {
  group = "boberc"
  version = "0.1.0-SNAPSHOT"
}

subprojects {
  // Emit Java 11 bytecode: compiler plugins built with boberc (and their users) may target JVM 11.
  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
      compilerOptions {
        jvmTarget = JvmTarget.JVM_11
      }
    }
    tasks.withType<JavaCompile>().configureEach {
      options.release = 11
    }
  }
}
