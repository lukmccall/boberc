package boberc.compiler.runners

import boberc.compiler.services.configurePlugin
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.codegen.AbstractJvmBlackBoxCodegenTestBase
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

/**
 * Compiles test data (template declarations) with the boberc plugin and runs `box()`.
 * The IR dump shows the `template(::f)` intrinsic replaced with the embedded model.
 */
open class AbstractJvmBoxTest : AbstractJvmBlackBoxCodegenTestBase(FirParser.LightTree) {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return EnvironmentBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) = with(builder) {
    super.configure(this)
    defaultDirectives {
      +CodegenTestDirectives.DUMP_IR
      +JvmEnvironmentConfigurationDirectives.FULL_JDK
      +CodegenTestDirectives.IGNORE_DEXING // Avoids loading R8 from the classpath.
    }
    configurePlugin()
  }
}
