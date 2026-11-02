package boberc.model

/*
 * The template model: a compiler-independent, serializable description of a function (signature + body).
 *
 * It mirrors the shape of fully resolved, not-yet-lowered IR, but every reference to a declaration is symbolic
 * (class ids, callable ids and signatures) so that the model can be re-materialized in a different compilation:
 *  - the signature is materialized into FIR (see `boberc.fir`), so generated declarations are visible to
 *    resolution of user code;
 *  - the body is materialized into IR (see `boberc.ir`).
 */

/** Root of the model: a captured `@Template` function or template lambda. */
public class FunctionTemplate(
  public val name: String,
  public val visibility: TVisibility,
  public val isOperator: Boolean,
  public val isInfix: Boolean,
  public val typeParameters: List<TTypeParameter>,
  /** Extension receiver of the template, if any. When generated as a member it becomes the dispatch receiver. */
  public val receiverType: TType?,
  public val parameters: List<TParameter>,
  public val returnType: TType,
  public val body: TExpr.Block?,
  /**
   * Captured from a lambda (`template { a: Int -> ... }`). Lambda templates have no receiver; their parameters
   * bind to the target function's parameters aligned from the end (see [boberc.template]).
   */
  public val isLambda: Boolean = false,
  /** Splice values bound when the template was created (values captured by a template lambda). Not encoded. */
  public val boundSplices: Map<String, Any?> = emptyMap(),
) {
  /** Parameters that become parameters of the generated function. */
  public val regularParameters: List<TParameter> = parameters.filter { !it.isSplice }

  /** Compile-time parameters which must be provided on instantiation. */
  public val spliceParameters: List<TParameter> = parameters.filter { it.isSplice }

  /** Whether the template must be generated as a member (it has a [TType.This] receiver). */
  public val requiresOwnerClass: Boolean
    get() = receiverType is TType.This || (isLambda && regularParameters.firstOrNull()?.type is TType.This)

  /**
   * Parameters that become value parameters of a *generated* function. For a lambda generated as a member,
   * a leading `This` parameter is the dispatch receiver instead.
   */
  public fun signatureParameters(asMember: Boolean): List<TParameter> {
    val regular = regularParameters
    return if (isLambda && asMember && regular.firstOrNull()?.type == TType.This(isNullable = false)) regular.drop(1) else regular
  }

  /** Returns a copy with [values] bound to splice parameters (merged with already bound ones). */
  public fun bindSplices(values: Map<String, Any?>): FunctionTemplate = FunctionTemplate(
    name, visibility, isOperator, isInfix, typeParameters, receiverType, parameters, returnType, body,
    isLambda, boundSplices + values,
  )

  /** Human-readable, deterministic rendering of the whole model. Useful in tests and for debugging. */
  public fun render(): String = SExpr.pretty(TemplateCodec.encodeToSExpr(this))

  /** Compact serialized form (this is what the boberc compiler plugin embeds into the plugin binary). */
  public fun encode(): String = TemplateCodec.encode(this)

  /**
   * Binds values for the [spliceParameters] and (optionally) renames the generated function.
   * Validates splice values eagerly so mistakes surface in the plugin, not in generated code.
   */
  public fun instantiate(name: String = this.name, splices: Map<String, Any?> = emptyMap()): TemplateInstance {
    val all = boundSplices + splices
    val expected = spliceParameters.associateBy { it.name }
    val unknown = all.keys - expected.keys
    require(unknown.isEmpty()) { "Template '${this.name}' has no splice parameters named $unknown" }
    val missing = expected.keys - all.keys
    require(missing.isEmpty()) { "Template '${this.name}': no values provided for splice parameters $missing" }
    for ((spliceName, value) in all) {
      checkSpliceValue(expected.getValue(spliceName), value)
    }
    return TemplateInstance(this, name, all)
  }

  override fun toString(): String = "FunctionTemplate($name)"

  public companion object {
    public fun decode(encoded: String): FunctionTemplate = TemplateCodec.decode(encoded)
  }
}

/** A template together with the values of its splice parameters: everything needed to generate a declaration. */
public class TemplateInstance internal constructor(
  public val template: FunctionTemplate,
  public val name: String,
  public val splices: Map<String, Any?>,
) {
  override fun toString(): String = "TemplateInstance($name from ${template.name}, splices=$splices)"
}

public enum class TVisibility { PUBLIC, INTERNAL, PROTECTED, PRIVATE }

public enum class TVariance { INVARIANT, IN, OUT }

public class TTypeParameter(
  public val name: String,
  public val variance: TVariance,
  public val isReified: Boolean,
  public val bounds: List<TType>,
)

public class TParameter(
  public val name: String,
  public val type: TType,
  public val isSplice: Boolean,
  public val varargElementType: TType?,
  public val isCrossinline: Boolean,
  public val isNoinline: Boolean,
  public val defaultValue: TExpr?,
)

// ------------------------------------------------------------------ types

public sealed class TType {
  public abstract val isNullable: Boolean

  /** A class type. [classId] uses the `ClassId.asString()` format, e.g. `kotlin/collections/List`. */
  public data class Class(
    val classId: String,
    val arguments: List<TTypeArgument>,
    override val isNullable: Boolean,
  ) : TType()

  /** A reference to the template function's own type parameter, by index. */
  public data class TypeParameter(val index: Int, override val isNullable: Boolean) : TType()

  /** The class the declaration is generated into; see [boberc.This]. */
  public data class This(override val isNullable: Boolean) : TType()

  /**
   * A class known by its fully qualified name only (from a named [boberc.Mirror]); resolved in the target
   * compilation, see [ClassNames.classIdCandidates].
   */
  public data class Named(val fqName: String, val arguments: List<TTypeArgument>, override val isNullable: Boolean) : TType()

  /** [boberc.Dynamic]: a value whose class is only known in the target compilation. */
  public data class Dynamic(override val isNullable: Boolean) : TType()
}

public sealed class TTypeArgument {
  public data object Star : TTypeArgument()
  public data class Projection(val variance: TVariance, val type: TType) : TTypeArgument()
}

// ------------------------------------------------------------------ references

/** A value visible in the template body. */
public sealed class TValueRef {
  /** The template's extension receiver (the dispatch receiver when generated as a member). */
  public data object Receiver : TValueRef()

  /** A template parameter, indexed in [FunctionTemplate.parameters] (splices included). */
  public data class Parameter(val index: Int) : TValueRef()

  /** A local variable / lambda parameter / catch parameter declared inside the body. */
  public data class Local(val id: Int) : TValueRef()
}

public enum class TAccessor { FUNCTION, GETTER, SETTER }

/** Symbolic reference to a callable that is re-resolved in the target compilation. */
public sealed class TCallee {
  /**
   * A function or property accessor.
   * @property packageFqName e.g. `kotlin.collections`
   * @property className relative class name (`Outer.Inner`) for members, `null` for top-level callables
   * @property signature see `boberc.ir.IrSignatures`; disambiguates overloads
   */
  public data class Callable(
    val packageFqName: String,
    val className: String?,
    val name: String,
    val accessor: TAccessor,
    val signature: String,
  ) : TCallee()

  /** An IR builtin operator (`EQEQ`, `less`, `CHECK_NOT_NULL`, ...) from `kotlin.internal.ir`. */
  public data class Builtin(val name: String, val signature: String) : TCallee()
}

// ------------------------------------------------------------------ expressions

public enum class TDynamicOp {
  /** `receiver.name` */
  GET_MEMBER,
  /** `receiver.name = arguments[0]` */
  SET_MEMBER,
  /** `receiver.name(arguments)` */
  CALL_MEMBER,
  /** top-level / static function `name(arguments)` */
  CALL_FUNCTION,
  /** top-level property `name` */
  GET_PROPERTY,
  /** constructor of class `name` */
  NEW_INSTANCE,
  /** the object `name` */
  GET_OBJECT,
  /** `receiver is name` */
  IS_INSTANCE,
  /** `receiver as name` */
  CAST,
  /** A call of the [boberc.FunctionRef] spliced as `name` (with `receiver` for members and extensions). */
  CALL_REF,
  /** The [boberc.ExprRef] spliced as `name`, inserted (copied) here. */
  REF_GET,
  /** Assignment of `arguments[0]` to the value the [boberc.ExprRef] `name` refers to. */
  REF_SET,
  /** The [boberc.ExprListRef] `name` expanded into arguments (only inside varargs and by-name calls). */
  LIST_SPREAD,
  /** The [boberc.ExprListRef] `name` as statements. */
  LIST_RUN,
  /** `T::class.java` of the [boberc.TypeRef] `name`. */
  TYPE_JAVA_CLASS,
  /** `receiver` cast to the [boberc.TypeRef] `name`, unless it already has exactly that type. */
  COERCE,
  /** The [boberc.FieldRef] `name`, read (from `receiver`, unless static). */
  FIELD_GET,
  /** The [boberc.FieldRef] `name` assigned `arguments[0]`. */
  FIELD_SET,
}

public enum class TTargetInfo { NAME, PARAMETER_COUNT, HAS_ANNOTATION }

public enum class TParameterInfo { NAME, INDEX, VALUE, HAS_ANNOTATION, IS_SUBTYPE_OF }

public enum class TConstKind { NULL, BOOLEAN, CHAR, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, STRING }

/** Expressions and statements of a template body. [origin]s carry IR statement origins by debug name. */
public sealed class TExpr {
  public data class Const(val kind: TConstKind, val value: String?, val type: TType) : TExpr()

  public data class GetValue(val ref: TValueRef) : TExpr()

  public data class SetValue(val ref: TValueRef, val value: TExpr, val origin: String?) : TExpr()

  /** Declaration of a local variable. Only valid as a statement. */
  public data class Variable(
    val id: Int,
    val name: String,
    val type: TType,
    val isVar: Boolean,
    val origin: String?,
    val initializer: TExpr?,
  ) : TExpr() {
    public companion object {
      /** [origin] of `val x = boberc.temporary(...)`: generated as a compiler temporary named `tmp_N`. */
      public const val TEMPORARY_ORIGIN: String = "BOBERC_TEMPORARY"
    }
  }

  /** Call of a function / accessor; [arguments] follow the callee's full parameter list (receivers included). */
  public data class Call(
    val callee: TCallee,
    val typeArguments: List<TType>,
    val arguments: List<TExpr?>,
    val type: TType,
    val origin: String?,
  ) : TExpr()

  public data class ConstructorCall(
    val classId: String,
    val signature: String,
    /** Type arguments of the class followed by type arguments of the constructor itself. */
    val typeArguments: List<TType>,
    val constructorTypeArgumentsCount: Int,
    val arguments: List<TExpr?>,
    val type: TType,
    val origin: String?,
  ) : TExpr()

  public data class GetObject(val classId: String, val type: TType) : TExpr()

  public data class GetEnumEntry(val classId: String, val entryName: String, val type: TType) : TExpr()

  /** Static (Java) field read, e.g. `System.out`. */
  public data class GetField(val classId: String, val fieldName: String, val receiver: TExpr?, val type: TType) : TExpr()

  public data class ClassReference(val classType: TType, val type: TType) : TExpr()

  public data class GetClass(val argument: TExpr, val type: TType) : TExpr()

  /** Return from the template function ([targetId] = 0) or from the lambda with the given id. */
  public data class Return(val targetId: Int, val value: TExpr) : TExpr()

  public data class Block(
    val statements: List<TExpr>,
    val type: TType,
    val origin: String?,
    val isComposite: Boolean,
  ) : TExpr()

  public data class Branch(val condition: TExpr, val result: TExpr, val isElse: Boolean)

  public data class When(val branches: List<Branch>, val type: TType, val origin: String?) : TExpr()

  public data class StringConcat(val arguments: List<TExpr>, val type: TType) : TExpr()

  /** [operator] is the name of an `IrTypeOperator` constant. */
  public data class TypeOperator(val operator: String, val typeOperand: TType, val argument: TExpr, val type: TType) : TExpr()

  public data class Loop(
    val id: Int,
    val isDoWhile: Boolean,
    val label: String?,
    val condition: TExpr,
    val body: TExpr?,
    val origin: String?,
  ) : TExpr()

  public data class Break(val loopId: Int) : TExpr()

  public data class Continue(val loopId: Int) : TExpr()

  public data class Throw(val value: TExpr) : TExpr()

  public data class Catch(val parameter: Variable, val result: TExpr)

  public data class Try(val tryResult: TExpr, val catches: List<Catch>, val finallyExpression: TExpr?, val type: TType) : TExpr()

  public data class Vararg(val elements: List<TExpr>, val elementType: TType, val type: TType) : TExpr()

  /** `*array` inside a [Vararg]. */
  public data class Spread(val expression: TExpr) : TExpr()

  public enum class LambdaParameterKind { EXTENSION_RECEIVER, REGULAR }

  public data class LambdaParameter(val id: Int, val kind: LambdaParameterKind, val name: String, val type: TType)

  /**
   * Late-bound access resolved by name in the target compilation (see `boberc/Dynamic.kt` and mirrors).
   * [name] must evaluate to a string when the template is applied: a constant or a splice.
   */
  public data class Dynamic(
    val op: TDynamicOp,
    val name: TExpr,
    /** Dispatch receiver for members; the extension receiver for top-level extensions. */
    val receiver: TExpr?,
    /** Extension receiver of a *member* extension (the dispatch receiver is [receiver]). */
    val extensionReceiver: TExpr?,
    /** Explicit type arguments of the callee (from mirror calls); inferred when empty. */
    val typeArguments: List<TType>,
    /** Positional arguments and [NamedArgument]s. */
    val arguments: List<TExpr>,
    val type: TType,
  ) : TExpr()

  /** `typeArgs(t1, t2)` inside a by-name call: explicit type arguments, each a spliced [boberc.TypeRef]. */
  public data class TypeArguments(val types: List<TExpr>) : TExpr()

  /** `arg("name", value)` inside a [Dynamic] call. [name] is resolved like [Dynamic.name]. */
  public data class NamedArgument(val name: TExpr, val value: TExpr) : TExpr()

  /**
   * `receiver.forEachProperty { name, value -> body }`: unrolled once per property when applied. The lambda
   * parameters are the locals [nameLocal] and [valueLocal]; [body] has no trailing `return`.
   */
  public data class ForEachProperty(
    val receiver: TExpr,
    val nameLocal: Int,
    val nameVariable: String,
    val valueLocal: Int,
    val valueVariable: String,
    val body: List<TExpr>,
  ) : TExpr()

  /** `forEachParameter { p -> body }`: unrolled once per regular parameter; `p` is the local [parameterLocal]. */
  public data class ForEachParameter(val parameterLocal: Int, val body: List<TExpr>) : TExpr()

  /** `mapParameters { p -> statements; result }` inside a by-name call: one argument per regular parameter. */
  public data class MapParameters(val parameterLocal: Int, val statements: List<TExpr>, val result: TExpr) : TExpr()

  /** A member of [boberc.targetFunction]: a compile-time fact about the function the template is applied to. */
  public data class TargetInfo(val op: TTargetInfo, val argument: TExpr?) : TExpr()

  /** A member of a [boberc.TemplateParameter] (`p.name`, `p.value`, `p.hasAnnotation("...")`, ...). */
  public data class ParameterInfo(val parameterLocal: Int, val op: TParameterInfo, val argument: TExpr?) : TExpr()

  public data class Lambda(
    val id: Int,
    val parameters: List<LambdaParameter>,
    val returnType: TType,
    val body: List<TExpr>,
    val type: TType,
    val origin: String?,
    val isSuspend: Boolean = false,
  ) : TExpr()
}

private fun checkSpliceValue(parameter: TParameter, value: Any?) {
  val type = parameter.type
  if (value == null) {
    require(type.isNullable) { "Splice '${parameter.name}' is not nullable, but null was provided" }
    return
  }
  val classId = (type as? TType.Class)?.classId
  val ok = when (classId) {
    "boberc/FunctionRef" -> value is boberc.FunctionRef
    "boberc/ExprRef" -> value is boberc.ExprRef
    "boberc/ExprListRef" -> value is boberc.ExprListRef
    "boberc/TypeRef" -> value is boberc.TypeRef
    "boberc/FieldRef" -> value is boberc.FieldRef
    "kotlin/String" -> value is String
    "kotlin/Int" -> value is Int
    "kotlin/Long" -> value is Long
    "kotlin/Short" -> value is Short
    "kotlin/Byte" -> value is Byte
    "kotlin/Char" -> value is Char
    "kotlin/Boolean" -> value is Boolean
    "kotlin/Float" -> value is Float
    "kotlin/Double" -> value is Double
    else -> false
  }
  require(ok) { "Splice '${parameter.name}' of type $classId cannot be bound to ${value::class.qualifiedName} value '$value'" }
}

/** Helpers for classes referenced by fully qualified name. */
public object ClassNames {
  /**
   * Possible class ids (`ClassId.asString()` format) for a fully qualified name, most likely first:
   * `a.b.Outer.Inner` -> `a/b/Outer.Inner`, `a/b.Outer.Inner`, `a.b/Outer/Inner`... A name already in class id
   * format (containing `/`) is returned as is.
   */
  public fun classIdCandidates(fqName: String): List<String> {
    if ('/' in fqName) return listOf(fqName)
    val segments = fqName.split('.')
    return (segments.size - 1 downTo 0).map { packageSize ->
      segments.take(packageSize).joinToString("/") + "/" + segments.drop(packageSize).joinToString(".")
    }.map { it.removePrefix("/") }
  }

  /** The first of the [classIdCandidates] of [fqName] that [lookup] resolves. */
  public inline fun <T : Any> firstResolved(fqName: String, lookup: (classId: String) -> T?): T? =
    classIdCandidates(fqName).firstNotNullOfOrNull(lookup)
}
