package boberc.compiler.runners

import boberc.compiler.services.configurePlugin
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.AbstractFirPhasedDiagnosticTest
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

/** Diagnostic tests; use `// RUN_PIPELINE_TILL: FIR2IR` for errors reported while capturing templates. */
open class AbstractJvmDiagnosticTest : AbstractFirPhasedDiagnosticTest(FirParser.LightTree) {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
        return EnvironmentBasedStandardLibrariesPathProvider
    }

    override fun configure(builder: TestConfigurationBuilder) = with(builder) {
        super.configure(builder)
        defaultDirectives {
            +FirDiagnosticsDirectives.DISABLE_GENERATED_FIR_TAGS
            +JvmEnvironmentConfigurationDirectives.FULL_JDK
            +CodegenTestDirectives.IGNORE_DEXING
        }
        configurePlugin()
    }
}
