package boberc.compiler

import boberc.compiler.ir.BobercTemplateCaptureExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * The boberc compiler plugin. It is applied to the module that *implements* a compiler plugin and captures
 * `@Template` functions into template models (see `boberc.model`), replacing `boberc.template(::f)` calls.
 */
class BobercComponentRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String
    get() = BuildConfig.KOTLIN_PLUGIN_ID
  override val supportsK2: Boolean
    get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    IrGenerationExtension.registerExtension(BobercTemplateCaptureExtension())
  }
}

@Suppress("unused") // Used via reflection.
class BobercCommandLineProcessor : CommandLineProcessor {
  override val pluginId: String
    get() = BuildConfig.KOTLIN_PLUGIN_ID
  override val pluginOptions: Collection<CliOption>
    get() = emptyList()

  override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
    error("Unexpected config option: '${option.optionName}'")
  }
}
