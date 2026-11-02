package boberc.example.runners

import boberc.example.ExamplePluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.runners.AbstractFirPhasedDiagnosticTest
import org.jetbrains.kotlin.test.runners.codegen.AbstractJvmBlackBoxCodegenTestBase
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.TestServices

/** Compiles *user code* with the example plugin (which was itself built from templates) and runs `box()`. */
open class AbstractExampleBoxTest : AbstractJvmBlackBoxCodegenTestBase(FirParser.LightTree) {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider =
    EnvironmentBasedStandardLibrariesPathProvider

  override fun configure(builder: TestConfigurationBuilder) = with(builder) {
    super.configure(this)
    defaultDirectives {
      +CodegenTestDirectives.DUMP_IR
      +FirDiagnosticsDirectives.FIR_DUMP
      +JvmEnvironmentConfigurationDirectives.FULL_JDK
      +CodegenTestDirectives.IGNORE_DEXING
    }
    useConfigurators(::ExamplePluginConfigurator)
  }
}

open class AbstractExampleDiagnosticTest : AbstractFirPhasedDiagnosticTest(FirParser.LightTree) {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider =
    EnvironmentBasedStandardLibrariesPathProvider

  override fun configure(builder: TestConfigurationBuilder) = with(builder) {
    super.configure(builder)
    defaultDirectives {
      +FirDiagnosticsDirectives.FIR_DUMP
      +FirDiagnosticsDirectives.DISABLE_GENERATED_FIR_TAGS
      +JvmEnvironmentConfigurationDirectives.FULL_JDK
      +CodegenTestDirectives.IGNORE_DEXING
    }
    useConfigurators(::ExamplePluginConfigurator)
  }
}

private class ExamplePluginConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
  private val registrar = ExamplePluginRegistrar()
  override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
    module: TestModule,
    configuration: CompilerConfiguration,
  ) {
    with(registrar) { registerExtensions(configuration) }
  }
}
