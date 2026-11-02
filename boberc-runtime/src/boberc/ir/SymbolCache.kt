package boberc.ir

import boberc.model.TCallee
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.fileOrNull

/**
 * Symbols resolved in one module, shared by every template materialized in it: a plugin applies many small
 * templates (one per export, field, argument...), and each lookup should go through the finder only once.
 * Stored on the module's IR, so it lives exactly as long as the compilation does.
 */
internal class SymbolCache private constructor() {
  /** Class id (`ClassId.asString()` format) -> class. */
  val classes = HashMap<String, IrClassSymbol>()

  /** Fully qualified name -> class, `null` when no class has that name. */
  val namedClasses = HashMap<String, IrClassSymbol?>()

  val callables = HashMap<TCallee, IrSimpleFunctionSymbol>()

  companion object {
    private var IrModuleFragment.bobercSymbols: SymbolCache? by irAttribute(copyByDefault = false)

    /** The cache of the module [scope] belongs to (a fresh one for a scope outside of any source file). */
    fun of(scope: IrDeclarationParent): SymbolCache {
      val module = ((scope as? IrFile) ?: (scope as? IrDeclaration)?.fileOrNull)?.module ?: return SymbolCache()
      return module.bobercSymbols ?: SymbolCache().also { module.bobercSymbols = it }
    }
  }
}
