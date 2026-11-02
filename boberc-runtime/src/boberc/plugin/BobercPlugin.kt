package boberc.plugin

import java.util.concurrent.ConcurrentHashMap
import boberc.fir.createMemberFunctionFromTemplate
import boberc.fir.createTopLevelFunctionFromTemplate
import boberc.ir.BobercTemplateException
import boberc.ir.IrTemplates
import boberc.model.FunctionTemplate
import boberc.model.TemplateInstance
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.*
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
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

  /** Generates / implements / redefines members of every class annotated with [annotationFqName]. */
  public fun classesAnnotatedWith(annotationFqName: String, block: MembersBuilder.() -> Unit) {
    members(ClassSelector.Annotated(FqName(annotationFqName)), block)
  }

  /** Generates / implements / redefines members of the class [fqName] (e.g. `"com.example.X"`). */
  public fun classNamed(fqName: String, block: MembersBuilder.() -> Unit) {
    members(ClassSelector.Named(fqName), block)
  }

  /**
   * Generates / implements / redefines members of every source class for which [predicate] holds, e.g.
   * `classesMatching({ it.hasProperty("id") }) { ... }`. See [ClassInfo] for what can be queried.
   */
  public fun classesMatching(predicate: (ClassInfo) -> Boolean, block: MembersBuilder.() -> Unit) {
    members(ClassSelector.Matching(predicate), block)
  }

  private fun members(selector: ClassSelector, block: MembersBuilder.() -> Unit) {
    val builder = MembersBuilder().apply(block)
    memberRules += MemberRule(selector, builder.functions.toList(), builder.replacements.toList())
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
    functionRules += FunctionRule(selector, FunctionsBuilder().apply(block).replacements.toList())
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
}

private fun requireName(template: FunctionTemplate, name: String) {
  require(!template.isLambda || name != template.name) { "A name is required to generate a function from a template lambda" }
}

@BobercDsl
public class FunctionsBuilder internal constructor() {
  internal val replacements = mutableListOf<Replacement>()

  /** Replaces the bodies of the matching functions with [template]. */
  public fun replaceBody(template: FunctionTemplate) {
    replacements += Replacement(name = null) { template }
  }

  /** Replaces bodies with a template chosen per function; return `null` to keep a function unchanged. */
  public fun replaceBody(template: BodyContext.() -> FunctionTemplate?) {
    replacements += Replacement(name = null, template)
  }
}

/** Information about the class a member is being generated into (FIR phase). */
public class MemberContext internal constructor(public val session: FirSession, public val owner: FirClassSymbol<*>) {
  public val classId: ClassId get() = owner.classId
  public val className: String get() = owner.classId.shortClassName.asString()

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
  class Annotated(val annotation: FqName) : FunctionSelector()
  class Named(val fqName: String) : FunctionSelector()

  fun matches(function: IrSimpleFunction): Boolean = when (this) {
    is Annotated -> function.hasAnnotation(annotation)
    is Named -> function.kotlinFqName.asString() == fqName
  }
}

internal class MemberRule(
  val selector: ClassSelector,
  val functions: List<MemberFunction>,
  val replacements: List<Replacement>,
)

internal class FunctionRule(val selector: FunctionSelector, val replacements: List<Replacement>)

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
  internal val topLevelPackages: Set<FqName> = topLevelFunctions.mapTo(HashSet()) { it.packageFqName }
  internal val topLevelCallableIds: Set<CallableId> = topLevelFunctions.mapTo(LinkedHashSet()) { it.callableId }

  /** Names of the members `replaceBody` rules redefine, to skip every other function quickly. */
  internal val replacedMemberNames: Set<String> = memberRules.flatMap { rule -> rule.replacements.mapNotNull { it.name } }.toSet()

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
  }
}

internal class BobercDeclarationGenerator(
  session: FirSession,
  private val plugin: BobercPlugin,
) : FirDeclarationGenerationExtension(session) {

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    plugin.memberRules.forEach { rule -> (rule.selector as? ClassSelector.Annotated)?.let { register(it.predicate) } }
  }

  /** FIR asks once per generated name: match each class against the rules only once. */
  private val functionsByClass = ConcurrentHashMap<FirClassSymbol<*>, List<MemberFunction>>()

  private fun functionsFor(owner: FirClassSymbol<*>): List<MemberFunction> = functionsByClass.getOrPut(owner) {
    if (owner.classKind != ClassKind.CLASS && owner.classKind != ClassKind.OBJECT) return@getOrPut emptyList()
    plugin.memberRules.filter { it.selector.matches(session, owner) }.flatMap { it.functions }
  }

  override fun getCallableNamesForClass(classSymbol: FirClassSymbol<*>, context: MemberGenerationContext): Set<Name> =
    functionsFor(classSymbol).mapTo(mutableSetOf()) { Name.identifier(it.name) }

  override fun generateFunctions(callableId: CallableId, context: MemberGenerationContext?): List<FirNamedFunctionSymbol> {
    val owner = context?.owner
    if (owner == null) {
      return plugin.topLevelFunctions
        .filter { it.callableId == callableId }
        .map { createTopLevelFunctionFromTemplate(it.packageFqName, it.instance, it.fileName).symbol }
    }
    val memberContext = MemberContext(session, owner)
    return functionsFor(owner)
      .filter { it.name == callableId.callableName.asString() }
      .map { function ->
        createMemberFunctionFromTemplate(owner, function.instance(memberContext), function.isOverride).symbol
      }
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelCallableIds(): Set<CallableId> = plugin.topLevelCallableIds

  override fun hasPackage(packageFqName: FqName): Boolean = packageFqName in plugin.topLevelPackages
}

// ---------------------------------------------------------------------------------------------------- IR

/**
 * IR part of boberc:
 *  - fills bodies of all functions generated from templates (i.e. with a [BobercTemplateKey] origin);
 *  - replaces bodies of existing functions matched by [plugin]'s `replaceBody` rules.
 *
 * Registered automatically by [BobercPlugin.registerExtensions]; register it yourself (without a plugin) if you
 * use `createMemberFunctionFromTemplate` / `createTopLevelFunctionFromTemplate` from your own FIR extensions.
 */
public class BobercIrGenerationExtension(private val plugin: BobercPlugin? = null) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
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
        }
        super.visitSimpleFunction(declaration)
      }
    })
  }

  private fun replaceBodyIfRequested(plugin: BobercPlugin, function: IrSimpleFunction, pluginContext: IrPluginContext) {
    val ownerClass = function.parent as? IrClass
    val name = function.name.asString()
    val memberReplacements = if (ownerClass == null || name !in plugin.replacedMemberNames) emptyList() else
      plugin.memberRules.filter { it.selector.matches(ownerClass) }.flatMap { it.replacements }.filter { it.name == name }
    val replacements = plugin.functionRules.filter { it.selector.matches(function) }.flatMap { it.replacements } + memberReplacements
    if (replacements.isEmpty()) return
    val context = BodyContext(function, pluginContext)
    val template = replacements.firstNotNullOfOrNull { it.template(context) } ?: return
    IrTemplates.replaceBody(function, template, pluginContext)
  }
}
