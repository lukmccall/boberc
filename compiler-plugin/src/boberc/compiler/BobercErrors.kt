package boberc.compiler

import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory

object BobercErrors : KtDiagnosticsContainer() {
  /** `template(...)` argument is not a callable reference to a `@Template` function. */
  val NOT_A_TEMPLATE_REFERENCE by error0<KtElement>()

  /** `shape(...)` argument is not a class literal of a `@Shape` class. */
  val NOT_A_SHAPE_REFERENCE by error0<KtElement>()

  /** `template(...)` / `shape(...)` refers to a declaration of another module, whose model is not available. */
  val TEMPLATE_FROM_OTHER_MODULE by error1<KtElement, String>()

  /** `template("name")` does not match any template of the module. */
  val TEMPLATE_NOT_FOUND by error1<KtElement, String>()

  /** `template("name")` matches several templates. */
  val AMBIGUOUS_TEMPLATE_NAME by error1<KtElement, String>()

  /** The template uses a construct that cannot be captured (or is not supported yet). */
  val UNSUPPORTED_IN_TEMPLATE by error1<KtElement, String>()

  override fun getRendererFactory(): BaseDiagnosticRendererFactory = BobercErrorMessages
}

object BobercErrorMessages : BaseDiagnosticRendererFactory() {
  override val MAP by KtDiagnosticFactoryToRendererMap("Boberc") { map ->
    map.put(
      BobercErrors.NOT_A_TEMPLATE_REFERENCE,
      "The argument of 'boberc.template()' must be a callable reference to a function annotated with '@Template'.",
    )
    map.put(
      BobercErrors.NOT_A_SHAPE_REFERENCE,
      "The argument of 'boberc.shape()' must be a class literal of a class annotated with '@Shape'.",
    )
    map.put(
      BobercErrors.TEMPLATE_FROM_OTHER_MODULE,
      "''{0}'' is declared in another module; templates and shapes can only be captured in the module declaring them.",
      TO_STRING,
    )
    map.put(
      BobercErrors.TEMPLATE_NOT_FOUND,
      "No ''@Template'' function named ''{0}'' in this module. The name must be a constant.",
      TO_STRING,
    )
    map.put(
      BobercErrors.AMBIGUOUS_TEMPLATE_NAME,
      "Several ''@Template'' functions match ''{0}''; use a fully qualified name or a callable reference.",
      TO_STRING,
    )
    map.put(BobercErrors.UNSUPPORTED_IN_TEMPLATE, "Unsupported in a boberc template or shape: {0}", TO_STRING)
  }
}
