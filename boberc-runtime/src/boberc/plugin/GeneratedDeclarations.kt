package boberc.plugin

import boberc.fir.BoundShape
import boberc.fir.ShapeParameter
import boberc.fir.bind
import boberc.model.ClassShape
import boberc.model.FunctionTemplate
import boberc.model.MemberShape
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/*
 * Declarations generated from `@Shape`s by the `bobercPlugin {}` DSL: supertypes, nested classes, companions and
 * top-level classes per selected class, and a sibling member per selected function. The DSL answers every FIR
 * callback (`getNestedClassifiersNames`, `getCallableNamesForClass`, `generateConstructors`, ...) for them and
 * fills their bodies in IR from the templates given here:
 *
 * ```
 * classesAnnotatedWith("my.Described", where = { it.isClass && !it.isGeneric }) {
 *   supertypes(describableShape)
 *   nestedClass("Describer", describerShape, typeArguments = { listOf(ownerType) }) {
 *     property("typeName") { val n = className; template { -> n } }
 *     function("describe", template { value: Dynamic -> ... })
 *   }
 *   companion(companionShape, typeArguments = { listOf(ownerType) }) { ... }
 * }
 * ```
 */

/** The default key of declarations generated from shapes when the plugin passes none. */
public object BobercShapeKey : GeneratedDeclarationKey() {
  override fun toString(): String = "BobercShape"
}

/** Bodies of the members of a class generated from a shape (IR phase). Members without one are left as they are. */
@BobercDsl
public class GeneratedClassBuilder internal constructor(private val shape: ClassShape) {
  internal var constructorBody: (IrClassContext.() -> FunctionTemplate?)? = null
  internal val properties = LinkedHashMap<String, IrClassContext.() -> FunctionTemplate>()
  internal val functions = LinkedHashMap<String, IrClassContext.() -> FunctionTemplate>()

  /**
   * The statements the generated constructors run after `super()` and the instance initializers. Without it,
   * constructors only do that.
   */
  public fun constructorBody(template: IrClassContext.() -> FunctionTemplate?) {
    constructorBody = template
  }

  /** Initializes the shape's property [name] with [template] (and gives it the default getter). */
  public fun property(name: String, template: FunctionTemplate) {
    property(name) { template }
  }

  /** [property] with a template created per class (e.g. a lambda capturing values). */
  public fun property(name: String, template: IrClassContext.() -> FunctionTemplate) {
    requireMember<MemberShape.Property>(name, "property")
    properties[name] = template
  }

  /** Fills the body of the shape's function [name] with [template]. */
  public fun function(name: String, template: FunctionTemplate) {
    function(name) { template }
  }

  /** [function] with a template created per class (e.g. a lambda capturing values). */
  public fun function(name: String, template: IrClassContext.() -> FunctionTemplate) {
    requireMember<MemberShape.Function>(name, "function")
    functions[name] = template
  }

  private inline fun <reified M : MemberShape> requireMember(name: String, kind: String) {
    require(shape.membersNamed(name).any { it is M }) { "Shape '${shape.name}' declares no $kind '$name'" }
  }

  internal fun build() = GeneratedBodies(constructorBody, properties.toMap(), functions.toMap())
}

/**
 * The class a rule selected, in the IR phase: the context of the bodies of declarations generated for it, and of
 * `ir { }` blocks.
 */
public class IrClassContext internal constructor(
  /** The selected class, e.g. the `@Described` class (not the nested class generated into it). */
  public val owner: IrClass,
  public val pluginContext: IrPluginContext,
) {
  public val className: String get() = owner.name.asString()
  public val classInfo: ClassInfo by lazy { owner.toClassInfo() }

  /** A nested class of [owner], generated or written by the user. */
  public fun nestedClass(name: String): IrClass? =
    owner.declarations.firstOrNull { it is IrClass && it.name.asString() == name } as IrClass?

  /** The companion of [owner], generated or written by the user. */
  public val companion: IrClass? get() = owner.companionObject()

  /** Looks up any class of the compilation (sources or libraries) by fully qualified name. */
  public fun findClass(fqName: String): ClassInfo? = pluginContext.findClassInfo(fqName)
}

/** The function a sibling is generated for, in the FIR phase. */
public class SiblingContext internal constructor(
  public val session: FirSession,
  public val owner: FirClassSymbol<*>,
  /** The selected function. */
  public val original: FirNamedFunctionSymbol,
)

// ------------------------------------------------------------------------------------------------ specs

internal class GeneratedBodies(
  val constructor: (IrClassContext.() -> FunctionTemplate?)?,
  val properties: Map<String, IrClassContext.() -> FunctionTemplate>,
  val functions: Map<String, IrClassContext.() -> FunctionTemplate>,
)

/** A class generated from [shape] for every selected class. */
internal sealed class ClassSpec(
  val shape: ClassShape,
  val key: GeneratedDeclarationKey,
  private val typeArguments: MemberContext.() -> List<ConeKotlinType>,
  val bodies: GeneratedBodies,
) {
  /** `owner` is the selected class. */
  fun bind(context: MemberContext): BoundShape = shape.bind(*context.typeArguments().toTypedArray())

  class Nested(val name: Name, shape: ClassShape, key: GeneratedDeclarationKey, typeArguments: MemberContext.() -> List<ConeKotlinType>, bodies: GeneratedBodies) :
    ClassSpec(shape, key, typeArguments, bodies)

  /** Generated when the class has no companion; otherwise the shape's members are added to the user's companion. */
  class Companion(shape: ClassShape, key: GeneratedDeclarationKey, typeArguments: MemberContext.() -> List<ConeKotlinType>, bodies: GeneratedBodies) :
    ClassSpec(shape, key, typeArguments, bodies)

  class TopLevel(
    val packageFqName: FqName,
    val name: (ClassInfo) -> String,
    shape: ClassShape,
    key: GeneratedDeclarationKey,
    typeArguments: MemberContext.() -> List<ConeKotlinType>,
    bodies: GeneratedBodies,
  ) : ClassSpec(shape, key, typeArguments, bodies)
}

/** Supertypes added to every selected class. */
internal class SupertypeSpec(val shape: ClassShape, private val typeArguments: MemberContext.() -> List<ConeKotlinType>) {
  fun bind(context: MemberContext): BoundShape = shape.bind(*context.typeArguments().toTypedArray())
}

/** A member generated next to every selected function, from the shape function [member]. */
internal class SiblingSpec(
  val shape: ClassShape,
  val member: String,
  val key: GeneratedDeclarationKey,
  val typeArguments: SiblingContext.() -> List<ConeKotlinType>,
  val parameters: SiblingContext.() -> List<ShapeParameter>,
  val name: SiblingContext.() -> Name,
  val body: (BodyContext.() -> FunctionTemplate?)?,
)

// ------------------------------------------------------------------------------------------------ sibling link

/** Which function a sibling was generated for, and from which rule. Kept on the generated FIR declaration. */
internal class SiblingOrigin(val original: FirNamedFunctionSymbol, val spec: SiblingSpec)

private object SiblingOriginKey : FirDeclarationDataKey()

internal var FirDeclaration.siblingOrigin: SiblingOrigin? by FirDeclarationDataRegistry.data(SiblingOriginKey)

/** The FIR link of an IR function generated as a sibling, read through its FIR metadata. */
internal val IrSimpleFunction.siblingOrigin: SiblingOrigin?
  get() = (metadata as? FirMetadataSource.Function)?.fir?.siblingOrigin

internal val IrSimpleFunction.firSymbol: FirNamedFunctionSymbol?
  get() = (metadata as? FirMetadataSource.Function)?.fir?.symbol as? FirNamedFunctionSymbol

internal fun IrDeclaration.isGeneratedWith(key: GeneratedDeclarationKey): Boolean =
  (origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey == key
