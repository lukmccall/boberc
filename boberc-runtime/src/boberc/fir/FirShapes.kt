package boberc.fir

import boberc.internal.toVisibility
import boberc.model.*
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.plugin.ClassBuildingContext
import org.jetbrains.kotlin.fir.plugin.FunctionBuildingContext
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/*
 * FIR generation from `@Shape` classes (see `boberc.Shape`). Each function wraps the matching
 * `org.jetbrains.kotlin.fir.plugin` builder, so a shape-generated declaration is the same declaration a
 * hand-written generator would build; `config` blocks run last and may override anything the shape set.
 *
 * The plugin keeps deciding *where* declarations go (FirDeclarationGenerationExtension callbacks, predicates);
 * the shape says *what* they look like:
 *
 * ```
 * override fun getCallableNamesForClass(classSymbol, context) = codecShape.callableNames
 * override fun generateProperties(callableId, context) =
 *   createPropertiesFromShape(owner, codecShape.bind(recordType), callableId.callableName, Key).map { it.symbol }
 * ```
 */

/** A [ClassShape] with its type parameters bound to the types of one generated class. */
public class BoundShape internal constructor(public val shape: ClassShape, public val typeArguments: List<ConeKotlinType>)

/**
 * A parameter the plugin computes for one generated declaration, inserted where the shape declares
 * `vararg name: ParameterSplice` (see `boberc.ParameterSplice`).
 */
public class ShapeParameter(
  public val name: Name,
  public val type: ConeKotlinType,
  public val isVararg: Boolean = false,
  public val isCrossinline: Boolean = false,
  public val isNoinline: Boolean = false,
)

/** Binds the type parameters of the shape, in declaration order. */
public fun ClassShape.bind(vararg typeArguments: ConeKotlinType): BoundShape {
  require(typeArguments.size == typeParameters.size) {
    "Shape '$name' has ${typeParameters.size} type parameter(s), got ${typeArguments.size} type argument(s)"
  }
  return BoundShape(this, typeArguments.toList())
}

/** Names of the shape's members, in declaration order. */
public val ClassShape.memberNameSet: Set<Name> get() = memberNames.mapTo(LinkedHashSet(), Name::identifier)

/** Names for `getCallableNamesForClass`: `<init>` when the shape declares constructors, then the members. */
public val ClassShape.callableNames: Set<Name>
  get() = buildSet {
    if (constructors.isNotEmpty()) add(SpecialNames.INIT)
    addAll(memberNameSet)
  }

/** Creates the nested class [name] of [owner] (kind, visibility, modality and supertypes from the shape). */
public fun FirExtension.createNestedClassFromShape(
  owner: FirClassSymbol<*>,
  name: Name,
  shape: BoundShape,
  key: GeneratedDeclarationKey,
  config: ClassBuildingContext.() -> Unit = {},
): FirRegularClass {
  val supertypes = supertypesOf(shape)
  return createNestedClass(owner, name, key, shape.shape.kind.toFir()) { configureClass(shape, supertypes, config) }
}

/** Creates the top-level class [classId] (kind, visibility, modality and supertypes from the shape). */
@ExperimentalTopLevelDeclarationsGenerationApi
public fun FirExtension.createTopLevelClassFromShape(
  classId: ClassId,
  shape: BoundShape,
  key: GeneratedDeclarationKey,
  config: ClassBuildingContext.() -> Unit = {},
): FirRegularClass {
  val supertypes = supertypesOf(shape)
  return createTopLevelClass(classId, key, shape.shape.kind.toFir()) { configureClass(shape, supertypes, config) }
}

/** Creates the companion object of [owner] with the supertypes of the shape (companions are public and final). */
public fun FirExtension.createCompanionObjectFromShape(
  owner: FirClassSymbol<*>,
  shape: BoundShape,
  key: GeneratedDeclarationKey,
  config: ClassBuildingContext.() -> Unit = {},
): FirRegularClass {
  require(shape.shape.kind != TClassKind.INTERFACE) {
    "Shape '${shape.shape.name}' is an interface and cannot describe a companion object"
  }
  val supertypes = supertypesOf(shape)
  return createCompanionObject(owner, key) {
    supertypes.forEach(::superType)
    config()
  }
}

private fun ClassBuildingContext.configureClass(
  shape: BoundShape,
  supertypes: List<ConeKotlinType>,
  config: ClassBuildingContext.() -> Unit,
) {
  visibility = shape.shape.visibility.toVisibility()
  if (shape.shape.isOpen) modality = Modality.OPEN
  supertypes.forEach(::superType)
  config()
}

/** The types the shape adds as supertypes, e.g. for `FirSupertypeGenerationExtension.computeAdditionalSupertypes`. */
public fun FirExtension.supertypesOf(shape: BoundShape): List<ConeKotlinType> =
  shape.shape.supertypes.map { typesOf(shape, owner = null).cone(it) }

/**
 * Creates the constructors the shape declares, for `generateConstructors`. Pass
 * [generateDelegatedNoArgConstructorCall] as for `createConstructor` (e.g. `true` for objects and companions).
 */
public fun FirExtension.createConstructorsFromShape(
  owner: FirClassSymbol<*>,
  shape: BoundShape,
  key: GeneratedDeclarationKey,
  generateDelegatedNoArgConstructorCall: Boolean = false,
  parameters: List<ShapeParameter> = emptyList(),
): List<FirConstructor> = shape.shape.constructors.map { constructor ->
  val types = typesOf(shape, owner)
  createConstructor(owner, key, constructor.isPrimary, generateDelegatedNoArgConstructorCall) {
    visibility = constructor.visibility.toVisibility()
    valueParameters(expand(constructor.parameters, parameters, types))
  }
}

private fun FunctionBuildingContext<*>.valueParameters(parameters: List<ShapeParameter>) {
  for (parameter in parameters) {
    valueParameter(parameter.name, parameter.type, parameter.isCrossinline, parameter.isNoinline, parameter.isVararg)
  }
}

/** The shape's parameters with the plugin's [spliced] ones in place of the `ParameterSplice` marker. */
private fun expand(declared: List<TParameter>, spliced: List<ShapeParameter>, types: ConeTypes): List<ShapeParameter> {
  require(spliced.isEmpty() || declared.any { it.isSplice }) { "Parameters were passed for a shape member without a `vararg ...: ParameterSplice`" }
  return declared.flatMap { parameter ->
    if (parameter.isSplice) spliced
    else listOf(
      ShapeParameter(
        Name.identifier(parameter.name), types.cone(parameter.type), parameter.varargElementType != null,
        parameter.isCrossinline, parameter.isNoinline,
      )
    )
  }
}

/** Creates the shape's properties named [name] as members of [owner], for `generateProperties`. */
public fun FirExtension.createPropertiesFromShape(
  owner: FirClassSymbol<*>,
  shape: BoundShape,
  name: Name,
  key: GeneratedDeclarationKey,
): List<FirProperty> = shape.shape.membersNamed(name.asString()).filterIsInstance<MemberShape.Property>().map { property ->
  val modality = generatedModality(shape.shape, property)
  val type = typesOf(shape, owner).cone(property.type)
  createMemberProperty(owner, key, name, type, isVal = !property.isVar, hasBackingField = modality != Modality.ABSTRACT) {
    visibility = property.visibility.toVisibility()
    this.modality = modality
    if (property.isOverride) status { isOverride = true }
  }
}

/** Creates the shape's functions named [name] as members of [owner], for `generateFunctions`. */
public fun FirExtension.createFunctionsFromShape(
  owner: FirClassSymbol<*>,
  shape: BoundShape,
  name: Name,
  key: GeneratedDeclarationKey,
): List<FirNamedFunction> = shape.shape.membersNamed(name.asString()).filterIsInstance<MemberShape.Function>().map { function ->
  createFunction(owner, shape, function, key, name, emptyList())
}

/**
 * Creates a member of [owner] from the shape function [member], named [name] (by default the shape's name, so
 * one shape function can stand for e.g. a generated overload of each user function), with [parameters] in place
 * of the shape's `vararg ...: ParameterSplice`.
 */
public fun FirExtension.createFunctionFromShape(
  owner: FirClassSymbol<*>,
  shape: BoundShape,
  member: String,
  key: GeneratedDeclarationKey,
  name: Name = Name.identifier(member),
  parameters: List<ShapeParameter> = emptyList(),
): FirNamedFunction {
  val function = shape.shape.membersNamed(member).filterIsInstance<MemberShape.Function>().singleOrNull()
    ?: error("Shape '${shape.shape.name}' declares no single function '$member'")
  return createFunction(owner, shape, function, key, name, parameters)
}

private fun FirExtension.createFunction(
  owner: FirClassSymbol<*>,
  shape: BoundShape,
  function: MemberShape.Function,
  key: GeneratedDeclarationKey,
  name: Name,
  parameters: List<ShapeParameter>,
): FirNamedFunction {
  val types = typesOf(shape, owner)
  return createMemberFunction(owner, key, name, types.cone(function.returnType)) {
    visibility = function.visibility.toVisibility()
    modality = generatedModality(shape.shape, function)
    if (function.isOverride || function.isOperator || function.isInfix || function.isExternal) {
      status {
        if (function.isOverride) isOverride = true
        if (function.isOperator) isOperator = true
        if (function.isInfix) isInfix = true
        if (function.isExternal) isExternal = true
      }
    }
    function.receiverType?.let { extensionReceiverType(types.cone(it)) }
    valueParameters(expand(function.parameters, parameters, types))
  }
}

/**
 * Members of interface shapes keep their modality. In class shapes, `abstract` only means "the body comes
 * later" (the IR phase fills it), so those members are generated final.
 */
private fun generatedModality(shape: ClassShape, member: MemberShape): Modality = when {
  shape.kind == TClassKind.INTERFACE -> member.modality.toFir()
  member.modality == TModality.OPEN -> Modality.OPEN
  else -> Modality.FINAL
}

/** Cone types of a bound shape: its type parameters are the bound type arguments; `This` is [owner]. */
private fun FirExtension.typesOf(shape: BoundShape, owner: FirClassSymbol<*>?) =
  ConeTypes(session, owner, "Shape '${shape.shape.name}'") { parameter ->
    val argument = shape.typeArguments.getOrNull(parameter.index)
      ?: error("Shape '${shape.shape.name}' references unknown type parameter #${parameter.index}")
    if (parameter.isNullable) argument.withNullability(true, session.typeContext) else argument
  }

private fun TClassKind.toFir(): ClassKind = when (this) {
  TClassKind.CLASS -> ClassKind.CLASS
  TClassKind.INTERFACE -> ClassKind.INTERFACE
  TClassKind.OBJECT -> ClassKind.OBJECT
}

private fun TModality.toFir(): Modality = when (this) {
  TModality.FINAL -> Modality.FINAL
  TModality.OPEN -> Modality.OPEN
  TModality.ABSTRACT -> Modality.ABSTRACT
}
