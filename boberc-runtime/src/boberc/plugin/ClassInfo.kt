package boberc.plugin

import boberc.model.ClassNames
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.name.ClassId

/**
 * A phase-independent view of a class of the target compilation, used to select classes
 * (`classesMatching { ... }`) and to compute splice values.
 *
 * Only *declared* members are listed (no inherited, synthetic or plugin-generated ones), by name: during FIR
 * generation member types are not resolved yet, so names are what is reliably available in every phase.
 */
public class ClassInfo internal constructor(
    /** e.g. `com.example.Outer.Inner` */
    public val fqName: String,
    /** e.g. `com/example/Outer.Inner`, accepted wherever a class name string is. */
    public val classId: String,
    public val simpleName: String,
    public val packageName: String,
    public val isObject: Boolean,
    public val isInterface: Boolean,
    public val propertyNames: List<String>,
    public val functionNames: List<String>,
) {
    public fun hasProperty(name: String): Boolean = name in propertyNames
    public fun hasFunction(name: String): Boolean = name in functionNames

    override fun toString(): String = "ClassInfo($fqName, properties=$propertyNames, functions=$functionNames)"

    internal companion object {
        fun create(classId: ClassId, kind: ClassKind, properties: List<String>, functions: List<String>) = ClassInfo(
            fqName = classId.asSingleFqName().asString(),
            classId = classId.asString(),
            simpleName = classId.shortClassName.asString(),
            packageName = classId.packageFqName.asString(),
            isObject = kind == ClassKind.OBJECT,
            isInterface = kind == ClassKind.INTERFACE,
            propertyNames = properties.distinct(),
            functionNames = functions.distinct(),
        )
    }
}

// ------------------------------------------------------------------------------------------------ FIR

// Raw declarations on purpose: scope-based APIs (`processAllDeclarations`) include plugin-generated members,
// and this is called while our own generator computes member names, which would recurse.
@OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)
internal fun FirClassSymbol<*>.toClassInfo(): ClassInfo {
    val declarations = (fir as? FirRegularClass)?.declarations.orEmpty()
        .filter { it.origin !is FirDeclarationOrigin.Plugin && it.origin !is FirDeclarationOrigin.Synthetic }
    return ClassInfo.create(
        classId, classKind,
        declarations.filterIsInstance<FirProperty>().map { it.name.asString() },
        declarations.filterIsInstance<FirNamedFunction>().map { it.name.asString() },
    )
}

internal fun FirSession.findClassInfo(fqName: String): ClassInfo? =
    ClassNames.firstResolved(fqName) { symbolProvider.getClassLikeSymbolByClassId(ClassId.fromString(it)) as? FirRegularClassSymbol }
        ?.toClassInfo()

// ------------------------------------------------------------------------------------------------ IR

internal fun IrClass.toClassInfo(): ClassInfo {
    val declarations = declarations.filter { it.isDeclaredMember() }
    return ClassInfo.create(
        classId ?: error("Local classes are not supported: $name"), kind,
        declarations.filterIsInstance<IrProperty>().map { it.name.asString() },
        declarations.filterIsInstance<IrSimpleFunction>().map { it.name.asString() },
    )
}

private fun IrDeclaration.isDeclaredMember(): Boolean {
    if ((this as? IrSimpleFunction)?.isFakeOverride == true || (this as? IrProperty)?.isFakeOverride == true) return false
    val origin = origin
    return origin !is IrDeclarationOrigin.GeneratedByPlugin &&
            origin != IrDeclarationOrigin.GENERATED_DATA_CLASS_MEMBER &&
            origin != IrDeclarationOrigin.ENUM_CLASS_SPECIAL_MEMBER
}

internal fun IrPluginContext.findClassInfo(fqName: String): ClassInfo? {
    val finder = finderForBuiltins()
    return ClassNames.firstResolved(fqName) { finder.findClass(ClassId.fromString(it)) }?.owner?.toClassInfo()
}
