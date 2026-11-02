package boberc.example

import boberc.Dynamic
import boberc.Mirror
import boberc.Shape
import boberc.call
import boberc.fir.bind
import boberc.fir.callableNames
import boberc.fir.createCompanionObjectFromShape
import boberc.fir.createConstructorsFromShape
import boberc.fir.createFunctionsFromShape
import boberc.fir.createNestedClassFromShape
import boberc.fir.createPropertiesFromShape
import boberc.fir.memberNameSet
import boberc.fir.supertypesOf
import boberc.forEachProperty
import boberc.ir.IrDeclarations
import boberc.ir.IrTemplates
import boberc.ir.constructorRef
import boberc.shape
import boberc.template
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/*
 * A plugin feature written with shapes (FIR) and templates (IR); no FIR or IR declaration is built by hand.
 *
 * For every `@Described class Point(val x: Int, val y: Int)`:
 *
 * ```
 * class Point : Describable                              supertype added       (DescribableShape)
 * ├── private class Describer : Describer<Point>         nested class          (DescriberShape)
 * │   ├── typeName = "Point"                             IR: field initializer (template)
 * │   └── describe(value) = "Point(x=1, y=2)"            IR: body              (template, forEachProperty)
 * └── companion object                                   the user's, or generated (DescribedCompanionShape)
 *     └── val describer: Describer<Point> = Describer()  IR: field initializer (template, constructorRef)
 * ```
 */

private val DESCRIBED = FqName("boberc.example.Described")
private val DESCRIBER_CLASS = Name.identifier("Describer")

@Mirror("boberc.example.Describable")
internal interface DescribableMirror

@Mirror("boberc.example.Describer")
internal interface DescriberMirror<T> {
  val typeName: String
  fun describe(value: T): String
}

/** Added to every `@Described` class. */
@Shape
private interface DescribableShape : DescribableMirror

/** The nested describer; `T` is the described class. Abstract members are generated final, bodies come from IR. */
@Shape
private abstract class DescriberShape<T> : DescriberMirror<T> {
  abstract override val typeName: String
  abstract override fun describe(value: T): String
}

/** Members added to the companion; the private constructor is used when the companion is generated. */
@Shape
private abstract class DescribedCompanionShape<T> private constructor() {
  abstract val describer: DescriberMirror<T>
}

private val describableShape = shape(DescribableShape::class)
private val describerShape = shape(DescriberShape::class)
private val companionShape = shape(DescribedCompanionShape::class)

internal object DescribedKey : GeneratedDeclarationKey() {
  override fun toString(): String = "Described"
}

// ---------------------------------------------------------------------------------------------------- FIR

internal class DescribedFirRegistrar : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +::DescribedSupertypeGenerator
    +::DescribedGenerator
  }
}

private val describedPredicate = DeclarationPredicate.create { annotated(DESCRIBED) }

private fun FirClassSymbol<*>.isDescribed(session: FirSession): Boolean =
  classKind == ClassKind.CLASS && typeParameterSymbols.isEmpty() && session.predicateBasedProvider.matches(describedPredicate, this)

internal class DescribedSupertypeGenerator(session: FirSession) : FirSupertypeGenerationExtension(session) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(describedPredicate)
  }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean =
    (declaration.symbol as? FirClassSymbol<*>)?.isDescribed(session) == true

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ) = supertypesOf(describableShape.bind()).filter { added -> resolvedSupertypes.none { it.coneType.classId == added.classId } }
}

internal class DescribedGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(describedPredicate)
  }

  @OptIn(SymbolInternals::class) // companionObjectSymbol
  override fun getNestedClassifiersNames(classSymbol: FirClassSymbol<*>, context: NestedClassGenerationContext): Set<Name> {
    if (!classSymbol.isDescribed(session)) return emptySet()
    val hasCompanion = (classSymbol as? FirRegularClassSymbol)?.companionObjectSymbol != null
    return if (hasCompanion) setOf(DESCRIBER_CLASS) else setOf(DESCRIBER_CLASS, SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (!owner.isDescribed(session)) return null
    return when (name) {
      DESCRIBER_CLASS -> createNestedClassFromShape(owner, name, describerShape.bind(owner.type()), DescribedKey).symbol
      SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT ->
        createCompanionObjectFromShape(owner, companionShape.bind(owner.type()), DescribedKey).symbol
      else -> null
    }
  }

  override fun getCallableNamesForClass(classSymbol: FirClassSymbol<*>, context: MemberGenerationContext): Set<Name> = when {
    classSymbol.isGeneratedDescriber() -> describerShape.callableNames
    // A companion written by the user keeps its own constructor.
    classSymbol.isDescribedCompanion() -> if (classSymbol.isOurs()) companionShape.callableNames else companionShape.memberNameSet
    else -> emptySet()
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val owner = context.owner
    val described = owner.described() ?: return emptyList()
    return when {
      owner.isGeneratedDescriber() -> createConstructorsFromShape(owner, describerShape.bind(described.type()), DescribedKey)
      owner.isDescribedCompanion() && owner.isOurs() ->
        createConstructorsFromShape(owner, companionShape.bind(described.type()), DescribedKey, generateDelegatedNoArgConstructorCall = true)
      else -> emptyList()
    }.map { it.symbol }
  }

  override fun generateProperties(callableId: CallableId, context: MemberGenerationContext?): List<FirPropertySymbol> {
    val owner = context?.owner ?: return emptyList()
    val described = owner.described() ?: return emptyList()
    val shape = when {
      owner.isGeneratedDescriber() -> describerShape
      owner.isDescribedCompanion() -> companionShape
      else -> return emptyList()
    }
    return createPropertiesFromShape(owner, shape.bind(described.type()), callableId.callableName, DescribedKey).map { it.symbol }
  }

  override fun generateFunctions(callableId: CallableId, context: MemberGenerationContext?): List<FirNamedFunctionSymbol> {
    val owner = context?.owner ?: return emptyList()
    if (!owner.isGeneratedDescriber()) return emptyList()
    val described = owner.described() ?: return emptyList()
    return createFunctionsFromShape(owner, describerShape.bind(described.type()), callableId.callableName, DescribedKey).map { it.symbol }
  }

  private fun FirClassSymbol<*>.type() = constructType(emptyArray(), isMarkedNullable = false)

  private fun FirClassSymbol<*>.isOurs() = (origin as? FirDeclarationOrigin.Plugin)?.key == DescribedKey

  private fun FirClassSymbol<*>.isGeneratedDescriber() = isOurs() && name == DESCRIBER_CLASS

  private fun FirClassSymbol<*>.isDescribedCompanion() = isCompanion && described() != null

  /** The `@Described` class this nested class belongs to. */
  private fun FirClassSymbol<*>.described(): FirClassSymbol<*>? {
    val outer = classId.outerClassId ?: return null
    val symbol = session.symbolProvider.getClassLikeSymbolByClassId(outer) as? FirClassSymbol<*> ?: return null
    return symbol.takeIf { it.isDescribed(session) }
  }
}

// ---------------------------------------------------------------------------------------------------- IR

/** Fills the bodies of everything [DescribedGenerator] declared. */
internal class DescribedIrGeneration : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
      override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

      override fun visitClass(declaration: IrClass) {
        if (declaration.hasAnnotation(DESCRIBED)) fill(declaration, pluginContext)
        super.visitClass(declaration)
      }
    })
  }

  private fun fill(described: IrClass, context: IrPluginContext) {
    val describer = described.declarations.filterIsInstance<IrClass>().single { it.name == DESCRIBER_CLASS }
    val companion = described.companionObject()!!
    val typeName = described.name.asString()

    for (declaration in describer.declarations) {
      if (!declaration.isOurs()) continue
      when (declaration) {
        is IrConstructor -> IrTemplates.constructorBody(declaration, null, context)
        is IrProperty -> IrDeclarations.initializeProperty(declaration, template { -> typeName }, context)
        is IrSimpleFunction -> IrTemplates.replaceBody(declaration, template { value: Dynamic ->
          buildString {
            append(typeName)
            append("(")
            var first = true
            value.forEachProperty { name, propertyValue ->
              if (!first) append(", ")
              first = false
              append("$name=$propertyValue")
            }
            append(")")
          }
        }, context)
      }
    }

    val newDescriber = constructorRef(describer.primaryConstructor!!)
    for (declaration in companion.declarations) {
      if (!declaration.isOurs()) continue
      when (declaration) {
        is IrConstructor -> IrTemplates.constructorBody(declaration, null, context)
        is IrProperty -> IrDeclarations.initializeProperty(declaration, template { -> newDescriber.call<Dynamic>() }, context)
      }
    }
  }

  private fun IrDeclaration.isOurs() =
    (origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey == DescribedKey
}
