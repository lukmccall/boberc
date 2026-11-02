package boberc.compiler.services

import boberc.compiler.BobercComponentRegistrar
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices
import java.io.File

fun TestConfigurationBuilder.configurePlugin() {
  useConfigurators(::BobercRegistrarConfigurator, ::BobercRuntimeConfigurator)
  useCustomRuntimeClasspathProviders(::BobercRuntimeClasspathProvider)
}

private class BobercRegistrarConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
  private val registrar = BobercComponentRegistrar()
  override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
    module: TestModule,
    configuration: CompilerConfiguration,
  ) {
    with(registrar) { registerExtensions(configuration) }
  }
}

/** Puts `boberc-runtime` on the compile classpath of test sources (`@Template`, `This`, `template()`). */
private class BobercRuntimeConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
  override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
    configuration.addJvmClasspathRoots(bobercRuntimeClasspath)
  }
}

private class BobercRuntimeClasspathProvider(testServices: TestServices) : RuntimeClasspathProvider(testServices) {
  override fun runtimeClassPaths(module: TestModule): List<File> = bobercRuntimeClasspath
}

private val bobercRuntimeClasspath: List<File> by lazy {
  val property = System.getProperty("bobercRuntime.classpath")
    ?: error("Unable to get a valid classpath from 'bobercRuntime.classpath' property")
  property.split(File.pathSeparator).map(::File)
}
