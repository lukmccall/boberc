package boberc.ir

import boberc.BobercInternalApi
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classId

/**
 * Overload-disambiguating signatures of IR functions.
 *
 * The same renderer is used by the boberc compiler plugin when it captures a call in a template and by the
 * runtime when it re-resolves that call in the target compilation, so it only has to be *consistent*, not
 * pretty. Nullability and variance are ignored (they cannot distinguish overloads and vary for Java declarations),
 * but type arguments are kept: some overloads differ only by them, e.g. `sumOf(selector: (T) -> Int)` and
 * `sumOf(selector: (T) -> Long)`.
 *
 * Format: `<type parameter count>|<kind><type>,<kind><type>...` where kind is `c` (context), `e` (extension
 * receiver) or `r` (regular), e.g. `1|ekotlin/collections/Iterable<#T>,rkotlin/Function1<#T,kotlin/Int>`.
 */
@BobercInternalApi
public object IrSignatures {
    public fun of(function: IrFunction): String = buildString {
        append(function.typeParameters.size)
        append('|')
        function.parameters
            .filter { it.kind != IrParameterKind.DispatchReceiver }
            .joinTo(this, ",") { parameter ->
                val kind = when (parameter.kind) {
                    IrParameterKind.Context -> "c"
                    IrParameterKind.ExtensionReceiver -> "e"
                    else -> "r"
                }
                kind + render(parameter.type)
            }
    }

    private fun render(type: IrType): String {
        val simple = type as? IrSimpleType ?: return "*"
        val classifier = when (val classifier = simple.classifier) {
            is IrClassSymbol -> classifier.owner.classIdOrName()
            // Type parameters are rendered by name only: their bounds may refer back to them.
            is IrTypeParameterSymbol -> return "#" + classifier.owner.name.asString()
            else -> return "*"
        }
        if (simple.arguments.isEmpty()) return classifier
        return simple.arguments.joinToString(",", prefix = "$classifier<", postfix = ">") { argument ->
            when (argument) {
                is IrStarProjection -> "*"
                is IrTypeProjection -> render(argument.type)
            }
        }
    }

    private fun IrClass.classIdOrName(): String = classId?.asString() ?: name.asString()
}
