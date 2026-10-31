package boberc.plugin

import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory

/** Errors reported by plugins built with boberc, in the compilation of *user* code. */
public object BobercRuntimeErrors : KtDiagnosticsContainer() {
    /** A template does not fit the function it is applied to (a redefined or generated body). */
    public val TEMPLATE_NOT_APPLICABLE: org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1<String> by error1<KtElement, String>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = BobercRuntimeErrorMessages
}

internal object BobercRuntimeErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP by KtDiagnosticFactoryToRendererMap("Boberc") { map ->
        map.put(BobercRuntimeErrors.TEMPLATE_NOT_APPLICABLE, "{0}", TO_STRING)
    }
}
