package boberc.plugin

import java.util.concurrent.ConcurrentHashMap
import boberc.fir.ShapeParameter
import boberc.fir.bind
import boberc.fir.callableNames
import boberc.fir.createCompanionObjectFromShape
import boberc.fir.createConstructorsFromShape
import boberc.fir.createFunctionFromShape
import boberc.fir.createFunctionsFromShape
import boberc.fir.createMemberFunctionFromTemplate
import boberc.fir.createNestedClassFromShape
import boberc.fir.createPropertiesFromShape
import boberc.fir.createTopLevelClassFromShape
import boberc.fir.createTopLevelFunctionFromTemplate
import boberc.fir.memberNameSet
import boberc.fir.supertypesOf
import boberc.ir.BobercTemplateException
import boberc.ir.IrDeclarations
import boberc.ir.IrTemplates
import boberc.model.ClassNames
import boberc.model.ClassShape
import boberc.model.FunctionTemplate
import boberc.model.MemberShape
import boberc.model.TClassKind
import boberc.model.TemplateInstance
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.*
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/**
 * Declarative description of a compiler plugin whose generated code comes from templates.
 *
 * ```
 * val plugin = bobercPlugin {
 *     classesAnnotatedWith("my.Greeter") {
 *         function(template(This::greet)) { mapOf("className" to className) }  // new member
 *         implement("toString") {                                              // override an inherited member
 *             val name = className
 *             template { self: This -> "$name@${self.hashCode()}" }
 *         }
 *         replaceBody("compute", template { x: Int -> x * 2 })                 // redefine an existing member
 *         nestedClass("Helper", helperShape) { function("help", template { -> "..." }) }  // declarations from shapes
 *     }
 *     functionsAnnotatedWith("my.Stub") {                                      // redefine annotated functions
 *         replaceBody(template { a: Int, b: Int -> a + b })
 *     }
 *     topLevelFunction("my.generated", template(::fizzBuzz))                  // new top-level function
 * }
 * ```
 * Register it from your `CompilerPluginRegistrar` with [BobercPlugin.registerExtensions].
 */
public fun bobercPlugin(block: BobercPluginBuilder.() -> Unit): BobercPlugin =
  BobercPluginBuilder().apply(block).build()

@DslMarker
public annotation class BobercDsl

@BobercDsl
public class BobercPluginBuilder internal constructor() {
  private val memberRules = mutableListOf<MemberRule>()
  private val functionRules = mutableListOf<FunctionRule>()
  private val topLevel = mutableListOf<TopLevelFunction>()

  /**
   * Generates / implements / redefines members of every class annotated with [annotationFqName] for which [where]
   * holds (e.g. `where = { it.isClass && !it.isGeneric }`; it is checked in FIR and in IR alike).
   */
  public fun classesAnnotatedWith(
    annotationFqName: String,
    where: (ClassInfo) -> Boolean = { true },
    block: MembersBuilder.() -> Unit,
  ) {
    members(ClassSelector.Annotated(FqName(annotationFqName)), where, block)
  }

  /** Generates / implements / redefines members of the class [fqName] (e.g. `"com.example.X"`). */
  public fun classNamed(fqName: String, block: MembersBuilder.() -> Unit) {
    members(ClassSelector.Named(fqName), { true }, block)
  }

  /**
   * Generates / implements / redefines members of every source class for which [predicate] holds, e.g.
   * `classesMatching({ it.hasProperty("id") }) { ... }`. See [ClassInfo] for what can be queried.
   */
  public fun classesMatching(predicate: (ClassInfo) -> Boolean, block: MembersBuilder.() -> Unit) {
    members(ClassSelector.Matching(predicate), { true }, block)
  }

  private fun members(selector: ClassSelector, where: (ClassInfo) -> Boolean, block: MembersBuilder.() -> Unit) {
    val builder = MembersBuilder().apply(block)
    require(selector !is ClassSelector.Matching || builder.classes.none { it is ClassSpec.TopLevel }) {
      "topLevelClass needs classesAnnotatedWith or classNamed: FIR lists top-level classes before it can match classes by structure"
    }
    memberRules += MemberRule(
      selector, where, builder.functions.toList(), builder.replacements.toList(), builder.supertypes.toList(),
      builder.classes.toList(), builder.irBlocks.toList(),
    )
  }

  /** Redefines (replaces the body of) every function annotated with [annotationFqName]. */
  public fun functionsAnnotatedWith(annotationFqName: String, block: FunctionsBuilder.() -> Unit) {
    functions(FunctionSelector.Annotated(FqName(annotationFqName)), block)
  }

  /** Redefines the function(s) with the fully qualified name [fqName]: `"com.example.compute"` or `"com.example.X.compute"`. */
  public fun functionsNamed(fqName: String, block: FunctionsBuilder.() -> Unit) {
    functions(FunctionSelector.Named(fqName), block)
  }

  private fun functions(selector: FunctionSelector, block: FunctionsBuilder.() -> Unit) {
    val builder = FunctionsBuilder().apply(block)
    functionRules += FunctionRule(selector, builder.replacements.toList(), builder.siblings.toList())
  }

  /** Generates a top-level function into [packageFqName]. */
  public fun topLevelFunction(
    packageFqName: String,
    template: FunctionTemplate,
    name: String = template.name,
    splices: Map<String, Any?> = emptyMap(),
    containingFileName: String = "BobercGenerated",
  ) {
    requireName(template, name)
    topLevel += TopLevelFunction(FqName(packageFqName), template.instantiate(name, splices), containingFileName)
  }

  internal fun build() = BobercPlugin(memberRules.toList(), functionRules.toList(), topLevel.toList())
}

@BobercDsl
public class MembersBuilder internal constructor() {
  internal val functions = mutableListOf<MemberFunction>()
  internal val replacements = mutableListOf<Replacement>()
  internal val supertypes = mutableListOf<SupertypeSpec>()
  internal val classes = mutableListOf<ClassSpec>()
  internal val irBlocks = mutableListOf<IrClassContext.() -> Unit>()

  /**
   * Generates [template] as a new member function named [name]. Splice values may depend on the owner class:
   * [splices] is evaluated once per class during FIR generation.
   */
  public fun function(
    template: FunctionTemplate,
    name: String = template.name,
    splices: MemberContext.() -> Map<String, Any?> = { emptyMap() },
  ) {
    requireName(template, name)
    add(name, isOverride = false) { template.instantiate(name, splices()) }
  }

  /** Generates [template] (typically a lambda) as a new member function named [name]. */
  public fun function(name: String, template: FunctionTemplate) {
    add(name, isOverride = false) { template.instantiate(name) }
  }

  /** Generates a new member function named [name] from a template created per class (e.g. a lambda capturing values). */
  public fun function(name: String, template: MemberContext.() -> FunctionTemplate) {
    add(name, isOverride = false) { template().instantiate(name) }
  }

  /**
   * Implements (overrides) the member [name] inherited by the class: an abstract interface function, or an open
   * one such as `toString`. The template signature must match the overridden function; with a lambda, a
   * leading `self: This` parameter is the receiver: `implement("toString", template { self: This -> "..." })`.
   */
  public fun implement(name: String, template: FunctionTemplate) {
    add(name, isOverride = true) { template.instantiate(name) }
  }

  /** [implement] with a template created per class. */
  public fun implement(name: String, template: MemberContext.() -> FunctionTemplate) {
    add(name, isOverride = true) { template().instantiate(name) }
  }

  private fun add(name: String, isOverride: Boolean, instance: MemberContext.() -> TemplateInstance) {
    functions += MemberFunction(name, isOverride, instance)
  }

  /**
   * Redefines the existing, user-written member function(s) named [name]: their body is replaced by [template].
   * `external` functions get a body (and stop being `external`).
   */
  public fun replaceBody(name: String, template: FunctionTemplate) {
    replacements += Replacement(name) { template }
  }

  /** [replaceBody] with a template chosen per function; return `null` to keep a function unchanged. */
  public fun replaceBody(name: String, template: BodyContext.() -> FunctionTemplate?) {
    replacements += Replacement(name, template)
  }

  /**
   * Adds the supertypes of [shape] to the class (those it doesn't have already). [typeArguments] bind the shape's
   * type parameters; [MemberContext.ownerType] is the class itself.
   */
  public fun supertypes(shape: ClassShape, typeArguments: MemberContext.() -> List<ConeKotlinType> = { emptyList() }) {
    supertypes += SupertypeSpec(shape, typeArguments)
  }

  /**
   * Generates the nested class [name] from [shape]: its kind, visibility, supertypes, constructors and members.
   * [bodies] fills them in IR; [key] is the origin of every generated declaration. [typeArguments] bind the shape's
   * type parameters, e.g. to [MemberContext.ownerStarProjectedType] (the class's own type parameters are not in scope).
   */
  public fun nestedClass(
    name: String,
    shape: ClassShape,
    key: GeneratedDeclarationKey = BobercShapeKey,
    typeArguments: MemberContext.() -> List<ConeKotlinType> = { emptyList() },
    bodies: GeneratedClassBuilder.() -> Unit = {},
  ) {
    val nestedName = Name.identifier(name)
    require(classes.none { it is ClassSpec.Nested && it.name == nestedName }) { "Nested class '$name' is generated twice" }
    classes += ClassSpec.Nested(nestedName, shape, key, typeArguments, GeneratedClassBuilder(shape).apply(bodies).build())
  }

  /**
   * Gives the class a companion with the members of [shape]. When the class has no companion, one is generated with
   * the shape's supertypes and constructors; a companion the user wrote only gets the members.
   */
  public fun companion(
    shape: ClassShape,
    key: GeneratedDeclarationKey = BobercShapeKey,
    typeArguments: MemberContext.() -> List<ConeKotlinType> = { emptyList() },
    bodies: GeneratedClassBuilder.() -> Unit = {},
  ) {
    require(shape.kind != TClassKind.INTERFACE) { "Shape '${shape.name}' is an interface and cannot describe a companion object" }
    classes += ClassSpec.Companion(shape, key, typeArguments, GeneratedClassBuilder(shape).apply(bodies).build())
  }

  /**
   * Generates a top-level class into [packageFqName] for every selected class, named by [name] (which must give a
   * distinct name per class).
   */
  public fun topLevelClass(
    packageFqName: String,
    name: (ClassInfo) -> String,
    shape: ClassShape,
    key: GeneratedDeclarationKey = BobercShapeKey,
    typeArguments: MemberContext.() -> List<ConeKotlinType> = { emptyList() },
    bodies: GeneratedClassBuilder.() -> Unit = {},
  ) {
    classes += ClassSpec.TopLevel(FqName(packageFqName), name, shape, key, typeArguments, GeneratedClassBuilder(shape).apply(bodies).build())
  }

  /**
   * Runs [block] in IR for every selected class, after boberc filled the bodies of what it generated, e.g. to fill
   * them with IR the plugin computes itself.
   */
  public fun ir(block: IrClassContext.() -> Unit) {
    irBlocks += block
  }
}

private fun requireName(template: FunctionTemplate, name: String) {
  require(!template.isLambda || name != template.name) { "A name is required to generate a function from a template lambda" }
}

@BobercDsl
public class FunctionsBuilder internal constructor() {
  internal val replacements = mutableListOf<Replacement>()
  internal val siblings = mutableListOf<SiblingSpec>()

  /** Replaces the bodies of the matching functions with [template]. */
  public fun replaceBody(template: FunctionTemplate) {
    replacements += Replacement(name = null) { template }
  }

  /** Replaces bodies with a template chosen per function; return `null` to keep a function unchanged. */
  public fun replaceBody(template: BodyContext.() -> FunctionTemplate?) {
    replacements += Replacement(name = null, template)
  }

  /**
   * Generates a member next to every matching member function, from the function [member] of [shape]: named [name]
   * (by default the original's, i.e. an overload), with [parameters] where the shape declares
   * `vararg ...: ParameterSplice`. [body] fills it in IR (an `external` shape function needs none).
   *
   * In IR, [BodyContext.generatedSibling] finds the sibling of a function, and [BodyContext.generatedFrom] the
   * function of a sibling: exactly, not by name.
   */
  public fun sibling(
    shape: ClassShape,
    member: String,
    key: GeneratedDeclarationKey = BobercShapeKey,
    typeArguments: SiblingContext.() -> List<ConeKotlinType> = { emptyList() },
    parameters: SiblingContext.() -> List<ShapeParameter> = { emptyList() },
    name: SiblingContext.() -> Name = { original.name },
    body: (BodyContext.() -> FunctionTemplate?)? = null,
  ) {
    require(shape.membersNamed(member).count { it is MemberShape.Function } == 1) {
      "Shape '${shape.name}' declares no single function '$member'"
    }
    siblings += SiblingSpec(shape, member, key, typeArguments, parameters, name, body)
  }
}

/** Information about the class a member is being generated into (FIR phase). */
public class MemberContext internal constructor(public val session: FirSession, public val owner: FirClassSymbol<*>) {
  public val classId: ClassId get() = owner.classId
  public val className: String get() = owner.classId.shortClassName.asString()

  /**
   * The type of the owner class with its own type parameters as arguments (`Box<T>`). Only valid where those are in
   * scope: in its supertypes and members. Use [ownerStarProjectedType] in nested, companion and top-level classes.
   */
  public val ownerType: ConeKotlinType
    get() = owner.constructType(owner.typeParameterSymbols.map { it.toConeType() }.toTypedArray(), isMarkedNullable = false)

  /** The type of the owner class with star projections (`Box<*>`); the same as [ownerType] for a non-generic class. */
  public val ownerStarProjectedType: ConeKotlinType
    get() = owner.constructType(owner.typeParameterSymbols.map { ConeStarProjection }.toTypedArray(), isMarkedNullable = false)

  /** The owner class: its name and declared members. */
  public val classInfo: ClassInfo by lazy { owner.toClassInfo() }

  /** Looks up any class of the compilation (sources or libraries) by fully qualified name. */
  public fun findClass(fqName: String): ClassInfo? = session.findClassInfo(fqName)
}

/** Information about a function whose body is being replaced (IR phase). */
public class BodyContext internal constructor(public val function: IrSimpleFunction, public val pluginContext: IrPluginContext) {
  public val functionName: String get() = function.name.asString()
  public val className: String? get() = (function.parent as? IrClass)?.name?.asString()
  public val fqName: String get() = function.kotlinFqName.asString()

  /** The class declaring the function, if it is a member. */
  public val classInfo: ClassInfo? by lazy { (function.parent as? IrClass)?.toClassInfo() }

  /** Looks up any class of the compilation (sources or libraries) by fully qualified name. */
  public fun findClass(fqName: String): ClassInfo? = pluginContext.findClassInfo(fqName)

  /**
   * The member a `sibling(...)` rule generated for [function] (with [key], if given), or `null`. Pass it to a
   * template as `functionRef(...)` to call exactly that function.
   */
  public fun generatedSibling(key: GeneratedDeclarationKey? = null): IrSimpleFunction? {
    val owner = function.parent as? IrClass ?: return null
    val original = function.firSymbol ?: return null
    return owner.declarations.firstOrNull { declaration ->
      declaration is IrSimpleFunction && declaration.origin is IrDeclarationOrigin.GeneratedByPlugin &&
        (key == null || declaration.isGeneratedWith(key)) && declaration.siblingOrigin?.original == original
    } as IrSimpleFunction?
  }

  /** When [function] is a generated sibling: the function it was generated for. */
  public val generatedFrom: IrSimpleFunction? by lazy {
    val original = function.siblingOrigin?.original ?: return@lazy null
    (function.parent as? IrClass)?.declarations?.firstOrNull { it is IrSimpleFunction && it.firSymbol == original } as IrSimpleFunction?
  }
}

internal sealed class ClassSelector {
  class Annotated(val annotation: FqName) : ClassSelector() {
    val predicate = LookupPredicate.create { annotated(annotation) }
  }

  class Named(val fqName: String) : ClassSelector()

  class Matching(val predicate: (ClassInfo) -> Boolean) : ClassSelector()

  fun matches(session: FirSession, owner: FirClassSymbol<*>): Boolean = when (this) {
    is Annotated -> session.predicateBasedProvider.matches(predicate, owner)
    is Named -> owner.classId.asSingleFqName().asString() == fqName || owner.classId.asString() == fqName
    is Matching -> predicate(owner.toClassInfo())
  }

  fun matches(irClass: IrClass): Boolean = when (this) {
    is Annotated -> irClass.hasAnnotation(annotation)
    is Named -> irClass.kotlinFqName.asString() == fqName || irClass.classId?.asString() == fqName
    is Matching -> irClass.classId != null && predicate(irClass.toClassInfo())
  }
}

internal sealed class FunctionSelector {
  class Annotated(val annotation: FqName) : FunctionSelector() {
    val predicate = LookupPredicate.create { annotated(annotation) }
  }

  class Named(val fqName: String) : FunctionSelector()

  fun matches(function: IrSimpleFunction): Boolean = when (this) {
    is Annotated -> function.hasAnnotation(annotation)
    is Named -> function.kotlinFqName.asString() == fqName
  }

  fun matches(session: FirSession, function: FirNamedFunctionSymbol): Boolean = when (this) {
    is Annotated -> session.predicateBasedProvider.matches(predicate, function)
    is Named -> function.callableId.asSingleFqName().asString() == fqName
  }
}

internal class MemberRule(
  val selector: ClassSelector,
  val where: (ClassInfo) -> Boolean,
  val functions: List<MemberFunction>,
  val replacements: List<Replacement>,
  val supertypes: List<SupertypeSpec>,
  val classes: List<ClassSpec>,
  val irBlocks: List<IrClassContext.() -> Unit>,
) {
  fun matches(session: FirSession, owner: FirClassSymbol<*>): Boolean =
    selector.matches(session, owner) && where(owner.toClassInfo())

  // Local classes have no ClassInfo; `where` can't exclude them (FIR never generates into them anyway).
  fun matches(irClass: IrClass): Boolean =
    selector.matches(irClass) && (irClass.classId == null || where(irClass.toClassInfo()))

  /** The rule declares something boberc has to visit in IR for each selected class. */
  val hasIrClassWork: Boolean get() = classes.isNotEmpty() || irBlocks.isNotEmpty()
}

internal class FunctionRule(val selector: FunctionSelector, val replacements: List<Replacement>, val siblings: List<SiblingSpec>)

internal class MemberFunction(
  val name: String,
  val isOverride: Boolean,
  val instance: MemberContext.() -> TemplateInstance,
)

internal class Replacement(val name: String?, val template: BodyContext.() -> FunctionTemplate?)

internal class TopLevelFunction(val packageFqName: FqName, val instance: TemplateInstance, val fileName: String) {
  val callableId = CallableId(packageFqName, Name.identifier(instance.name))
}

public class BobercPlugin internal constructor(
  internal val memberRules: List<MemberRule>,
  internal val functionRules: List<FunctionRule>,
  internal val topLevelFunctions: List<TopLevelFunction>,
) {
  internal val topLevelPackages: Set<FqName> = topLevelFunctions.mapTo(HashSet()) { it.packageFqName } +
    memberRules.flatMap { rule -> rule.classes.filterIsInstance<ClassSpec.TopLevel>().map { it.packageFqName } }
  internal val topLevelCallableIds: Set<CallableId> = topLevelFunctions.mapTo(LinkedHashSet()) { it.callableId }

  /** Names of the members `replaceBody` rules redefine, to skip every other function quickly. */
  internal val replacedMemberNames: Set<String> = memberRules.flatMap { rule -> rule.replacements.mapNotNull { it.name } }.toSet()

  internal val hasSupertypes: Boolean = memberRules.any { it.supertypes.isNotEmpty() }
  internal val hasSiblings: Boolean = functionRules.any { it.siblings.isNotEmpty() }
  internal val hasTopLevelClasses: Boolean = memberRules.any { rule -> rule.classes.any { it is ClassSpec.TopLevel } }

  /** Registers FIR (signatures) and IR (bodies) extensions implementing this plugin. */
  public fun registerExtensions(storage: CompilerPluginRegistrar.ExtensionStorage) {
    with(storage) {
      FirExtensionRegistrarAdapter.registerExtension(BobercFirExtensionRegistrar(this@BobercPlugin))
      IrGenerationExtension.registerExtension(BobercIrGenerationExtension(this@BobercPlugin))
    }
  }
}

/** Marks declarations generated from templates; carries everything the IR phase needs to build the body. */
public class BobercTemplateKey(
  public val instance: TemplateInstance,
  public val isOverride: Boolean = false,
) : GeneratedDeclarationKey() {
  override fun toString(): String = "Boberc(${instance.name})"
}

// ---------------------------------------------------------------------------------------------------- FIR

internal class BobercFirExtensionRegistrar(private val plugin: BobercPlugin) : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +{ session: FirSession -> BobercDeclarationGenerator(session, plugin) }
    if (plugin.hasSupertypes) +{ session: FirSession -> BobercSupertypeGenerator(session, plugin) }
  }
}

private fun FirDeclarationPredicateRegistrar.registerSelectors(plugin: BobercPlugin) {
  plugin.memberRules.forEach { rule -> (rule.selector as? ClassSelector.Annotated)?.let { register(it.predicate) } }
  plugin.functionRules.forEach { rule ->
    if (rule.siblings.isNotEmpty()) (rule.selector as? FunctionSelector.Annotated)?.let { register(it.predicate) }
  }
}

private val FirClassSymbol<*>.isPluginGenerated: Boolean get() = origin is FirDeclarationOrigin.Plugin

private fun FirClassSymbol<*>.isGeneratedWith(key: GeneratedDeclarationKey): Boolean =
  (origin as? FirDeclarationOrigin.Plugin)?.key == key

/** A class generated from [spec] for the selected class [owner] (or the user's companion [spec] adds members to). */
private class GeneratedClass(val spec: ClassSpec, val owner: FirClassSymbol<*>, val declaresConstructors: Boolean)

/**
 * The FIR declarations of [plugin]: template functions, classes and members from shapes, siblings. Registered by
 * [BobercPlugin.registerExtensions]; a plugin with its own `FirExtensionRegistrar` registers it there (or a subclass,
 * to keep an existing name): `+{ session: FirSession -> BobercDeclarationGenerator(session, plugin) }`.
 */
@OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class, SymbolInternals::class, DirectDeclarationsAccess::class)
public open class BobercDeclarationGenerator(
  session: FirSession,
  private val plugin: BobercPlugin,
) : FirDeclarationGenerationExtension(session) {

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    registerSelectors(plugin)
  }

  /** FIR asks once per generated name: match each class against the rules only once. */
  private val rulesByClass = ConcurrentHashMap<FirClassSymbol<*>, List<MemberRule>>()

  private fun rulesFor(owner: FirClassSymbol<*>): List<MemberRule> = rulesByClass.getOrPut(owner) {
    plugin.memberRules.filter { it.matches(session, owner) }
  }

  private fun functionsFor(owner: FirClassSymbol<*>): List<MemberFunction> =
    if (owner.classKind != ClassKind.CLASS && owner.classKind != ClassKind.OBJECT) emptyList()
    else rulesFor(owner).flatMap { it.functions }

  /** Class specs apply to the user's classes only, never to what a plugin generated. */
  private fun classSpecsFor(owner: FirClassSymbol<*>): List<ClassSpec> =
    if (owner.isPluginGenerated) emptyList() else rulesFor(owner).flatMap { it.classes }

  // ------------------------------------------------------------------------------------------ classes

  private val generatedByClass = ConcurrentHashMap<FirClassSymbol<*>, List<GeneratedClass>>()

  /** What [classSymbol] was generated from (or, for a user-written companion, what adds members to it). */
  private fun generatedClasses(classSymbol: FirClassSymbol<*>): List<GeneratedClass> = generatedByClass.getOrPut(classSymbol) {
    topLevelClasses[classSymbol.classId]?.let { (owner, spec) ->
      return@getOrPut if (classSymbol.isGeneratedWith(spec.key)) listOf(GeneratedClass(spec, owner, declaresConstructors = true)) else emptyList()
    }
    val outerId = classSymbol.classId.outerClassId ?: return@getOrPut emptyList()
    val outer = session.symbolProvider.getClassLikeSymbolByClassId(outerId) as? FirClassSymbol<*> ?: return@getOrPut emptyList()
    val specs = classSpecsFor(outer)
    val companions = specs.filterIsInstance<ClassSpec.Companion>()
    specs.mapNotNull { spec ->
      when (spec) {
        is ClassSpec.Nested ->
          if (classSymbol.name == spec.name && classSymbol.isGeneratedWith(spec.key)) GeneratedClass(spec, outer, true) else null
        // Only the companion boberc generated (from the first companion spec) declares constructors.
        is ClassSpec.Companion -> if ((classSymbol as? FirRegularClassSymbol)?.isCompanion != true) null else
          GeneratedClass(spec, outer, declaresConstructors = spec === companions.first() && classSymbol.isGeneratedWith(spec.key))
        is ClassSpec.TopLevel -> null
      }
    }
  }

  private fun GeneratedClass.bound() = spec.bind(MemberContext(session, owner))

  override fun getNestedClassifiersNames(classSymbol: FirClassSymbol<*>, context: NestedClassGenerationContext): Set<Name> =
    classSpecsFor(classSymbol).mapNotNullTo(LinkedHashSet()) { spec ->
      when (spec) {
        is ClassSpec.Nested -> spec.name
        is ClassSpec.Companion ->
          if ((classSymbol as? FirRegularClassSymbol)?.companionObjectSymbol == null) SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT else null
        is ClassSpec.TopLevel -> null
      }
    }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    val specs = classSpecsFor(owner)
    val memberContext = MemberContext(session, owner)
    specs.filterIsInstance<ClassSpec.Nested>().firstOrNull { it.name == name }?.let { spec ->
      return createNestedClassFromShape(owner, name, spec.bind(memberContext), spec.key).symbol
    }
    if (name == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
      val spec = specs.filterIsInstance<ClassSpec.Companion>().firstOrNull() ?: return null
      return createCompanionObjectFromShape(owner, spec.bind(memberContext), spec.key).symbol
    }
    return null
  }

  /** Top-level classes by id, with the selected class and spec each is generated for. */
  private val topLevelClasses: Map<ClassId, Pair<FirClassSymbol<*>, ClassSpec.TopLevel>> by lazy {
    if (!plugin.hasTopLevelClasses) return@lazy emptyMap()
    buildMap {
      for (rule in plugin.memberRules) {
        val specs = rule.classes.filterIsInstance<ClassSpec.TopLevel>()
        if (specs.isEmpty()) continue
        for (owner in candidatesOf(rule.selector)) {
          if (owner.isPluginGenerated || !rule.matches(session, owner)) continue
          val info = owner.toClassInfo()
          for (spec in specs) {
            val classId = ClassId(spec.packageFqName, Name.identifier(spec.name(info)))
            check(classId !in this) { "boberc: top-level class $classId is generated for two classes" }
            put(classId, owner to spec)
          }
        }
      }
    }
  }

  private fun candidatesOf(selector: ClassSelector): List<FirClassSymbol<*>> = when (selector) {
    is ClassSelector.Annotated -> session.predicateBasedProvider.getSymbolsByPredicate(selector.predicate).filterIsInstance<FirClassSymbol<*>>()
    is ClassSelector.Named -> listOfNotNull(
      ClassNames.firstResolved(selector.fqName) { session.symbolProvider.getClassLikeSymbolByClassId(ClassId.fromString(it)) as? FirClassSymbol<*> }
    )
    is ClassSelector.Matching -> emptyList() // rejected when the plugin is built
  }

  override fun getTopLevelClassIds(): Set<ClassId> = topLevelClasses.keys

  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    val (owner, spec) = topLevelClasses[classId] ?: return null
    return createTopLevelClassFromShape(classId, spec.bind(MemberContext(session, owner)), spec.key).symbol
  }

  // ------------------------------------------------------------------------------------------ siblings

  /** The functions of [owner] a `sibling` rule selects, with the spec of each sibling. */
  private val siblingsByClass = ConcurrentHashMap<FirClassSymbol<*>, List<Pair<SiblingSpec, SiblingContext>>>()

  private fun siblingsOf(owner: FirClassSymbol<*>): List<Pair<SiblingSpec, SiblingContext>> = siblingsByClass.getOrPut(owner) {
    if (!plugin.hasSiblings || owner.isPluginGenerated) return@getOrPut emptyList()
    val functions = owner.declarationSymbols.filterIsInstance<FirNamedFunctionSymbol>()
    plugin.functionRules.filter { it.siblings.isNotEmpty() }.flatMap { rule ->
      functions.filter { rule.selector.matches(session, it) }.flatMap { original ->
        val context = SiblingContext(session, owner, original)
        rule.siblings.map { it to context }
      }
    }
  }

  // ------------------------------------------------------------------------------------------ members

  override fun getCallableNamesForClass(classSymbol: FirClassSymbol<*>, context: MemberGenerationContext): Set<Name> {
    val names = functionsFor(classSymbol).mapTo(LinkedHashSet()) { Name.identifier(it.name) }
    for (generated in generatedClasses(classSymbol)) {
      names += if (generated.declaresConstructors) generated.spec.shape.callableNames else generated.spec.shape.memberNameSet
    }
    siblingsOf(classSymbol).mapTo(names) { (spec, siblingContext) -> spec.name(siblingContext) }
    return names
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val owner = context.owner
    return generatedClasses(owner).filter { it.declaresConstructors }.flatMap { generated ->
      // Objects delegate to `Any()`.
      val isObject = generated.spec is ClassSpec.Companion || generated.spec.shape.kind == TClassKind.OBJECT
      createConstructorsFromShape(owner, generated.bound(), generated.spec.key, generateDelegatedNoArgConstructorCall = isObject)
    }.map { it.symbol }
  }

  override fun generateProperties(callableId: CallableId, context: MemberGenerationContext?): List<FirPropertySymbol> {
    val owner = context?.owner ?: return emptyList()
    return generatedClasses(owner).flatMap { generated ->
      createPropertiesFromShape(owner, generated.bound(), callableId.callableName, generated.spec.key)
    }.map { it.symbol }
  }

  override fun generateFunctions(callableId: CallableId, context: MemberGenerationContext?): List<FirNamedFunctionSymbol> {
    val owner = context?.owner
    if (owner == null) {
      return plugin.topLevelFunctions
        .filter { it.callableId == callableId }
        .map { createTopLevelFunctionFromTemplate(it.packageFqName, it.instance, it.fileName).symbol }
    }
    val name = callableId.callableName
    val memberContext = MemberContext(session, owner)
    val fromTemplates = functionsFor(owner)
      .filter { it.name == name.asString() }
      .map { function -> createMemberFunctionFromTemplate(owner, function.instance(memberContext), function.isOverride) }
    val fromShapes = generatedClasses(owner).flatMap { generated ->
      createFunctionsFromShape(owner, generated.bound(), name, generated.spec.key)
    }
    val siblings = siblingsOf(owner).filter { (spec, siblingContext) -> spec.name(siblingContext) == name }.map { (spec, siblingContext) ->
      val shape = spec.shape.bind(*spec.typeArguments(siblingContext).toTypedArray())
      createFunctionFromShape(owner, shape, spec.member, spec.key, name, spec.parameters(siblingContext)).also {
        it.siblingOrigin = SiblingOrigin(siblingContext.original, spec)
      }
    }
    return (fromTemplates + fromShapes + siblings).map { it.symbol }
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelCallableIds(): Set<CallableId> = plugin.topLevelCallableIds

  override fun hasPackage(packageFqName: FqName): Boolean = packageFqName in plugin.topLevelPackages
}

/**
 * Adds the supertypes of [plugin]'s `supertypes(...)` rules. Registered by [BobercPlugin.registerExtensions] when
 * there are any; a plugin with its own `FirExtensionRegistrar` registers it there (or a subclass).
 */
public open class BobercSupertypeGenerator(
  session: FirSession,
  private val plugin: BobercPlugin,
) : FirSupertypeGenerationExtension(session) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    registerSelectors(plugin)
  }

  private fun specsFor(owner: FirClassSymbol<*>): List<SupertypeSpec> =
    if (owner.isPluginGenerated) emptyList()
    else plugin.memberRules.filter { it.supertypes.isNotEmpty() && it.matches(session, owner) }.flatMap { it.supertypes }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean =
    (declaration.symbol as? FirClassSymbol<*>)?.let { specsFor(it).isNotEmpty() } == true

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    val owner = classLikeDeclaration.symbol as? FirClassSymbol<*> ?: return emptyList()
    val context = MemberContext(session, owner)
    return specsFor(owner)
      .flatMap { supertypesOf(it.bind(context)) }
      .filter { added -> resolvedSupertypes.none { it.coneType.classId == added.classId } }
      .distinctBy { it.classId }
  }
}

// ---------------------------------------------------------------------------------------------------- IR

/**
 * IR part of boberc:
 *  - fills bodies of all functions generated from templates (i.e. with a [BobercTemplateKey] origin);
 *  - fills bodies of declarations [plugin] generated from shapes, and runs its `ir { }` blocks;
 *  - replaces bodies of existing functions matched by [plugin]'s `replaceBody` rules.
 *
 * Registered automatically by [BobercPlugin.registerExtensions]; register it yourself (without a plugin) if you
 * use `createMemberFunctionFromTemplate` / `createTopLevelFunctionFromTemplate` from your own FIR extensions.
 */
public class BobercIrGenerationExtension(private val plugin: BobercPlugin? = null) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val selectedClasses = if (plugin == null) emptyList() else selectClasses(plugin, moduleFragment)
    val topLevelClasses = if (plugin?.hasTopLevelClasses == true) topLevelClassesOf(moduleFragment) else emptyMap()
    for ((rule, owner) in selectedClasses) fillGeneratedClasses(rule, owner, topLevelClasses, pluginContext)

    moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
      override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
      }

      override fun visitSimpleFunction(declaration: IrSimpleFunction) {
        val key = (declaration.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey as? BobercTemplateKey
        if (key != null && declaration.body == null) {
          if (key.isOverride && declaration.overriddenSymbols.isEmpty()) {
            throw BobercTemplateException(
              "boberc: implemented function '${declaration.name}' does not override anything in " +
                "${(declaration.parent as? IrClass)?.name}; check its name and signature"
            )
          }
          // Generated members have no source: failures are reported on the class they were generated into.
          IrTemplates.reportingFailures(declaration, pluginContext) {
            IrTemplates.generateBody(declaration, key.instance, pluginContext)
          }
        } else if (plugin != null && declaration.origin == IrDeclarationOrigin.DEFINED &&
          (declaration.body != null || declaration.isExternal) // `external` functions can be implemented
        ) {
          replaceBodyIfRequested(plugin, declaration, pluginContext)
        } else if (plugin?.hasSiblings == true && declaration.body == null && declaration.origin is IrDeclarationOrigin.GeneratedByPlugin) {
          fillSibling(declaration, pluginContext)
        }
        super.visitSimpleFunction(declaration)
      }
    })

    for ((rule, owner) in selectedClasses) {
      if (rule.irBlocks.isEmpty()) continue
      val context = IrClassContext(owner, pluginContext)
      rule.irBlocks.forEach { it(context) }
    }
  }

  /** The user's classes selected by a rule with IR work, collected before anything is changed. */
  private fun selectClasses(plugin: BobercPlugin, moduleFragment: IrModuleFragment): List<Pair<MemberRule, IrClass>> {
    val rules = plugin.memberRules.filter { it.hasIrClassWork }
    if (rules.isEmpty()) return emptyList()
    val selected = mutableListOf<Pair<MemberRule, IrClass>>()
    moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
      override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
      }

      override fun visitClass(declaration: IrClass) {
        if (declaration.origin !is IrDeclarationOrigin.GeneratedByPlugin) {
          rules.filter { it.matches(declaration) }.mapTo(selected) { it to declaration }
        }
        super.visitClass(declaration)
      }
    })
    return selected
  }

  private fun topLevelClassesOf(moduleFragment: IrModuleFragment): Map<ClassId, IrClass> =
    moduleFragment.files.flatMap { it.declarations }.filterIsInstance<IrClass>().mapNotNull { c -> c.classId?.let { it to c } }.toMap()

  private fun fillGeneratedClasses(rule: MemberRule, owner: IrClass, topLevel: Map<ClassId, IrClass>, pluginContext: IrPluginContext) {
    val context = IrClassContext(owner, pluginContext)
    for (spec in rule.classes) {
      val generated = when (spec) {
        is ClassSpec.Nested -> owner.declarations.firstOrNull { it is IrClass && it.name == spec.name && it.isGeneratedWith(spec.key) }
        is ClassSpec.Companion -> context.companion
        is ClassSpec.TopLevel -> topLevel[ClassId(spec.packageFqName, Name.identifier(spec.name(context.classInfo)))]
      } as IrClass? ?: continue // FIR did not generate it (e.g. the class failed to resolve)
      for (declaration in generated.declarations.toList()) {
        if (!declaration.isGeneratedWith(spec.key)) continue
        when (declaration) {
          is IrConstructor -> if (declaration.body == null) {
            IrTemplates.constructorBody(declaration, spec.bodies.constructor?.invoke(context), pluginContext)
          }
          is IrProperty -> spec.bodies.properties[declaration.name.asString()]?.let { template ->
            IrDeclarations.initializeProperty(declaration, template(context), pluginContext)
          }
          is IrSimpleFunction -> spec.bodies.functions[declaration.name.asString()]?.let { template ->
            IrTemplates.replaceBody(declaration, template(context), pluginContext)
          }
          else -> {}
        }
      }
    }
  }

  private fun fillSibling(function: IrSimpleFunction, pluginContext: IrPluginContext) {
    val origin = function.siblingOrigin ?: return
    val body = origin.spec.body ?: return
    if (!function.isGeneratedWith(origin.spec.key)) return
    val template = BodyContext(function, pluginContext).body() ?: return
    IrTemplates.replaceBody(function, template, pluginContext)
  }

  private fun replaceBodyIfRequested(plugin: BobercPlugin, function: IrSimpleFunction, pluginContext: IrPluginContext) {
    val ownerClass = function.parent as? IrClass
    val name = function.name.asString()
    val memberReplacements = if (ownerClass == null || name !in plugin.replacedMemberNames) emptyList() else
      plugin.memberRules.filter { it.matches(ownerClass) }.flatMap { it.replacements }.filter { it.name == name }
    val replacements = plugin.functionRules.filter { it.selector.matches(function) }.flatMap { it.replacements } + memberReplacements
    if (replacements.isEmpty()) return
    val context = BodyContext(function, pluginContext)
    val template = replacements.firstNotNullOfOrNull { it.template(context) } ?: return
    IrTemplates.replaceBody(function, template, pluginContext)
  }
}
