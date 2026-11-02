package boberc.ir

import boberc.BobercInternalApi
import boberc.internal.toVariance
import org.jetbrains.kotlin.ir.util.hasDefaultValue
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import boberc.model.*
import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrStarProjectionImpl
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.ir.util.render
import boberc.plugin.BobercRuntimeErrors
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/** Thrown when a template cannot be materialized in the target compilation. */
public class BobercTemplateException(message: String) : IllegalStateException(message)

/**
 * IR side of template materialization: fills the body (and default argument values) of a function whose
 * signature was previously generated from the same template in FIR.
 */
public object IrTemplates {
  /** Fills the body of [function], whose signature was generated in FIR from the same template. */
  public fun generateBody(function: IrSimpleFunction, instance: TemplateInstance, context: IrPluginContext) {
    IrTemplateMaterializer(context, function, instance).materialize()
  }

  /**
   * Replaces the body of an existing (user-written) [function] with the template's body. The template's
   * parameters (for a lambda: aligned from the end) are bound to the function's parameters and type-checked.
   */
  public fun replaceBody(function: IrSimpleFunction, instance: TemplateInstance, context: IrPluginContext) {
    generateBody(function, instance, context)
    // Implementing an `external` function: it now has a Kotlin body.
    function.isExternal = false
  }

  /**
   * Replaces the body of [function] with [template] (its splices bound, e.g. values captured by a template
   * lambda). If the template does not fit, reports `TEMPLATE_NOT_APPLICABLE` on the function, or on its class
   * when the function has no source (generated in IR), and returns `false` instead of throwing.
   */
  public fun replaceBody(function: IrSimpleFunction, template: FunctionTemplate, context: IrPluginContext): Boolean =
    reportingFailures(function, context) { replaceBody(function, template.instantiate(function.name.asString()), context) }

  /**
   * Fills [constructor]: `super()` (the superclass's constructor without arguments), the instance initializers, then
   * the statements of [template], if any. In the template, a leading `self: This` parameter is the class's `this`.
   */
  public fun constructorBody(constructor: IrConstructor, template: FunctionTemplate?, context: IrPluginContext) {
    val bodyTemplate = template ?: EMPTY_TEMPLATE
    IrTemplateMaterializer(context, constructor, bodyTemplate.instantiate("<init>")).materializeConstructorBody(constructor)
  }

  private val EMPTY_TEMPLATE = FunctionTemplate(
    "constructor", TVisibility.PUBLIC, false, false, emptyList(), null, emptyList(),
    TType.Class("kotlin/Unit", emptyList(), false), null, isLambda = true,
  )

  /**
   * Sets the body of [function] to [statements] built with [IrQuotes] in that function's scope (so they may declare
   * locals the later ones use), and fixes the parents of declarations in them.
   */
  public fun setBody(function: IrFunction, statements: List<IrStatement>, context: IrPluginContext) {
    function.body = context.irFactory.createBlockBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, statements)
    function.patchDeclarationParents(function.parent)
  }

  /** [generateBody] that reports failures like [replaceBody]. */
  public fun generateBody(function: IrSimpleFunction, template: FunctionTemplate, context: IrPluginContext): Boolean =
    reportingFailures(function, context) { generateBody(function, template.instantiate(function.name.asString()), context) }

  internal fun reportingFailures(function: IrSimpleFunction, context: IrPluginContext, action: () -> Unit): Boolean {
    try {
      action()
      return true
    } catch (e: BobercTemplateException) {
      val ownSource = function.startOffset >= 0 && function.fileOrNull != null
      val element: IrDeclaration = if (ownSource) function else (function.parent as? IrClass) ?: throw e
      val file = element.fileOrNull ?: throw e
      context.diagnosticReporter.at(element, file)
        .report(BobercRuntimeErrors.TEMPLATE_NOT_APPLICABLE, e.message ?: "template is not applicable")
      return false
    }
  }
}

/**
 * Materializes one template in a *scope*: the body of a function, of a constructor, or an expression / statements
 * placed anywhere inside a declaration (a function, a constructor, a field initializer...).
 *
 * [scopeFunction] is the function whose parameters, type parameters and receiver the template sees (null in a
 * field initializer); [scopeParent] is the parent of locals and lambdas the template creates.
 */
@OptIn(BobercInternalApi::class)
internal class IrTemplateMaterializer(
  private val context: IrPluginContext,
  private val scopeFunction: IrFunction?,
  private val instance: TemplateInstance,
  scopeParent: IrDeclarationParent = scopeFunction ?: error("a template needs a function or a declaration parent as scope"),
) {
  private val template = instance.template
  private val finder: DeclarationFinder = context.finderForBuiltins()
  private val builtIns = context.irBuiltIns
  private val factory = context.irFactory

  /** The function the template is applied to; templates using its parameters / name need one. */
  private val function: IrFunction
    get() = scopeFunction ?: fail("this template uses the parameters or the name of a function, but is applied outside of one")

  /** The class declaring the function / field: `This`. Also set for static members (no dispatch receiver). */
  private val ownerClass: IrClass? = scopeParent as? IrClass ?: (scopeParent as? IrDeclaration)?.parentClassOrNull
  private val isMember: Boolean = scopeFunction?.dispatchReceiverParameter != null

  /** `this` of the class in a constructor (constructors have no dispatch receiver parameter). */
  private val constructorThis: IrValueParameter? = (scopeFunction as? IrConstructor)?.constructedClass?.thisReceiver

  private val receiverParameter: IrValueParameter? = when {
    template.receiverType == null || template.isLambda -> null
    isMember -> scopeFunction?.dispatchReceiverParameter
    else -> scopeFunction?.parameters?.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
  }
  private val regularParameters = scopeFunction?.parameters.orEmpty().filter { it.kind == IrParameterKind.Regular }

  /** Template parameter index -> generated parameter (null for splices). */
  private val parameterMapping: List<IrValueParameter?> = if (template.isLambda) {
    // A leading `self: This` binds the dispatch receiver (in a constructor: the class's `this`); the other lambda
    // parameters bind to the remaining parameters (extension receiver included), aligned from the end, so they
    // work for any arity.
    val all = scopeFunction?.parameters.orEmpty()
    val dispatch = scopeFunction?.dispatchReceiverParameter ?: constructorThis
    val bound = template.parameters.withIndex().filter { !it.value.isSplice }
    val leadingThis = dispatch != null && bound.firstOrNull()?.value?.type == TType.This(isNullable = false)
    val aligned = if (leadingThis) bound.drop(1) else bound
    val targets = if (leadingThis) all.filter { it.kind != IrParameterKind.DispatchReceiver } else all
    val mapping = arrayOfNulls<IrValueParameter>(template.parameters.size)
    if (leadingThis) mapping[bound.first().index] = dispatch
    aligned.forEachIndexed { i, (templateIndex, _) -> mapping[templateIndex] = targets.getOrNull(targets.size - aligned.size + i) }
    mapping.toList()
  } else {
    var next = 0
    template.parameters.map { if (it.isSplice) null else regularParameters.getOrNull(next++) }
  }

  private val locals = HashMap<Int, IrValueDeclaration>()
  private val returnTargets = HashMap<Int, IrReturnTargetSymbol>()
  private val loops = HashMap<Int, IrLoop>()
  private val symbols = SymbolCache.of(scopeParent)

  /** Declaration parent for locals and lambdas created at the current position. */
  private var parent: IrDeclarationParent = scopeParent

  /** The body as an expression: its statements followed by the value it returns (no other `return`). */
  fun materializeExpression(): IrExpression {
    if (template.isLambda) validateLambdaParameters()
    var (statements, value) = bodyAndValue()
    // A `Unit` lambda ends with its last statement (`{ -> x.set(y) }`): that statement is the value.
    if (value == null && statements.lastOrNull() is IrExpression) {
      value = statements.last() as IrExpression
      statements = statements.dropLast(1)
    }
    val result = value ?: fail("an expression template must end with a value")
    return if (statements.isEmpty()) result else IrBlockImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, result.type, null, statements + result)
  }

  /** The body as statements (a trailing `Unit` value is dropped). */
  fun materializeStatements(): List<IrStatement> {
    if (template.isLambda) validateLambdaParameters()
    val (statements, value) = bodyAndValue()
    return tidy(statements + listOfNotNull(value))
  }

  /** `super()` to the superclass's no-argument constructor, the instance initializers, then the template's statements. */
  fun materializeConstructorBody(constructor: IrConstructor) {
    val constructedClass = constructor.constructedClass
    val superClass = constructedClass.superTypes.mapNotNull { it.classOrNull?.owner }.firstOrNull { it.kind == ClassKind.CLASS }
      ?: builtIns.anyClass.owner
    val superConstructor = superClass.declarations.filterIsInstance<IrConstructor>()
      .firstOrNull { c -> c.parameters.none { it.kind == IrParameterKind.Regular && it.defaultValue == null } }
      ?: fail("${superClass.name} has no constructor without arguments to delegate to")
    val prelude = listOf(
      IrDelegatingConstructorCallImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, builtIns.unitType, superConstructor.symbol, 0),
      IrInstanceInitializerCallImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, constructedClass.symbol, builtIns.unitType),
    )
    val statements = if (template.body == null) emptyList() else materializeStatements()
    constructor.body = factory.createBlockBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, prelude + statements)
  }

  /** Statements of the body, and the trailing returned value (an expression template's result), if any. */
  private fun bodyAndValue(): Pair<List<IrStatement>, IrExpression?> {
    val body = template.body ?: fail("template has no body")
    val statements = body.statements
    val last = statements.lastOrNull()
    val trailing = (last as? TExpr.Return)?.takeIf { it.targetId == 0 }
    val materialized = tidy((if (trailing != null) statements.dropLast(1) else statements).map(::statement))
    val value = trailing?.let { expr(it.value) }?.takeUnless { it is IrGetObjectValue && it.type.isUnit() }
    return materialized to value
  }

  private fun validateLambdaParameters() {
    template.parameters.forEachIndexed { index, parameter ->
      if (!parameter.isSplice && parameterMapping[index] == null) {
        fail("lambda parameter '${parameter.name}' has no matching parameter in the scope it is applied in")
      }
    }
  }

  fun materialize() {
    val function = function as? IrSimpleFunction ?: fail("only a function can get a body this way")
    validateShape()
    returnTargets[0] = function.symbol
    val body = template.body ?: fail("template has no body")
    function.body = factory.createBlockBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, tidy(body.statements.map(::statement)))
    template.parameters.forEachIndexed { index, parameter ->
      val defaultValue = parameter.defaultValue ?: return@forEachIndexed
      val target = parameterMapping[index] ?: return@forEachIndexed
      target.defaultValue = factory.createExpressionBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, expr(defaultValue))
    }
  }

  private fun validateShape() {
    if (template.isLambda) return validateLambdaShape()
    if (regularParameters.size != template.regularParameters.size) {
      fail("expected ${template.regularParameters.size} parameters, but the generated function has ${regularParameters.size}")
    }
    if (function.typeParameters.size != template.typeParameters.size) {
      fail("expected ${template.typeParameters.size} type parameters, but the generated function has ${function.typeParameters.size}")
    }
    if (template.receiverType != null && receiverParameter == null) {
      fail("the template has a receiver, but the generated function has neither a dispatch nor an extension receiver")
    }
    val receiverType = template.receiverType
    if (ownerClass != null && isMember && receiverType is TType.Class) {
      val required = classSymbol(receiverType.classId)
      if (!ownerClass.defaultType.isSubtypeOfClass(required)) {
        fail("is generated into ${ownerClass.name}, which is not a subtype of the template receiver ${receiverType.classId}")
      }
    }
  }

  private fun validateLambdaShape() {
    val bound = template.regularParameters
    if (bound.size > function.parameters.size) {
      fail("the lambda has ${bound.size} parameters, but '${function.name}' has only ${function.parameters.size} (receivers included)")
    }
    template.parameters.forEachIndexed { index, parameter ->
      if (parameter.isSplice) return@forEachIndexed
      val target = parameterMapping[index] ?: fail("lambda parameter '${parameter.name}' has no matching parameter in '${function.name}'")
      if (parameter.type is TType.This) {
        if (ownerClass == null || target.type.classOrNull != ownerClass.symbol) {
          fail("lambda parameter '${parameter.name}: This' is bound to '${target.name}: ${target.type.render()}'; `This` must bind to the dispatch receiver")
        }
      } else if (!isAssignable(target.type, type(parameter.type))) {
        fail("lambda parameter '${parameter.name}: ${type(parameter.type).render()}' cannot be bound to '${target.name}: ${target.type.render()}'")
      }
    }
    val returnType = type(template.returnType)
    // A `Dynamic` result (e.g. of a by-name call) is checked where its precise type is known.
    if (template.returnType !is TType.Dynamic && !isAssignable(returnType, function.returnType)) {
      fail("the lambda returns ${returnType.render()}, but '${function.name}' returns ${function.returnType.render()}")
    }
  }

  /** Conservative class-level check (type arguments and type parameters are not checked). */
  private fun isAssignable(from: IrType, to: IrType): Boolean {
    val toClass = to.classOrNull ?: return true
    if (from.isNothing() || from.isNullableNothing()) return true
    if (from.isMarkedNullable() && !to.isMarkedNullable()) return false
    val fromClass = from.classOrNull ?: return true
    return fromClass == toClass || from.isSubtypeOfClass(toClass)
  }

  private fun fail(message: String): Nothing =
    throw BobercTemplateException(
      "boberc: cannot apply ${if (template.isLambda) "template lambda" else "template '${template.name}'"} " +
          "to '${instance.name}': $message"
    )

  // ------------------------------------------------------------------------------------------------ types

  private fun type(t: TType): IrType {
    val base: IrType = when (t) {
      is TType.Class -> classSymbol(t.classId).typeWithArguments(t.arguments.map(::typeArgument))
      is TType.TypeParameter -> scopeFunction?.typeParameters?.getOrNull(t.index)?.defaultType
        ?: fail("unknown type parameter #${t.index}")
      is TType.This -> ownerClass?.defaultType ?: fail("`This` can only be used in functions declared in a class")
      is TType.Named -> namedClass(t.fqName).typeWithArguments(t.arguments.map(::typeArgument))
      // A value of a target-only class whose precise type is not known statically.
      is TType.Dynamic -> builtIns.anyNType
    }
    return if (t.isNullable) base.makeNullable() else base
  }

  private fun typeArgument(a: TTypeArgument): IrTypeArgument = when (a) {
    TTypeArgument.Star -> IrStarProjectionImpl
    is TTypeArgument.Projection -> makeTypeProjection(type(a.type), a.variance.toVariance())
  }


  private fun classSymbol(classId: String): IrClassSymbol = symbols.classes.getOrPut(classId) {
    finder.findClass(ClassId.fromString(classId))
      ?: fail("class $classId is not available in this compilation (is the library declaring it on the classpath?)")
  }

  // ------------------------------------------------------------------------------------------------ symbols

  private fun callable(callee: TCallee): IrSimpleFunctionSymbol = symbols.callables.getOrPut(callee) {
    when (callee) {
      is TCallee.Builtin -> builtinFunctions()
        .firstOrNull { it.owner.name.asString() == callee.name && IrSignatures.of(it.owner) == callee.signature }
        ?: fail("IR builtin ${callee.name}(${callee.signature}) is not available")
      is TCallee.Callable -> {
        val id = CallableId(
          FqName(callee.packageFqName),
          callee.className?.let(::FqName),
          Name.guessByFirstCharacter(callee.name),
        )
        val candidates: List<IrSimpleFunctionSymbol> = when (callee.accessor) {
          TAccessor.FUNCTION -> finder.findFunctions(id).toList()
          TAccessor.GETTER -> finder.findProperties(id).mapNotNull { it.owner.getter?.symbol }
          TAccessor.SETTER -> finder.findProperties(id).mapNotNull { it.owner.setter?.symbol }
        }
        candidates.firstOrNull { IrSignatures.of(it.owner) == callee.signature }
          ?: fail(
            "cannot resolve ${callee.accessor.name.lowercase()} $id with signature ${callee.signature}; " +
                "candidates: ${candidates.map { IrSignatures.of(it.owner) }}"
          )
      }
    }
  }

  private fun builtinFunctions(): List<IrSimpleFunctionSymbol> = with(builtIns) {
    listOf(
      eqeqSymbol, eqeqeqSymbol, checkNotNullSymbol, andandSymbol, ororSymbol,
      noWhenBranchMatchedExceptionSymbol, illegalArgumentExceptionSymbol, throwCceSymbol, throwIseSymbol,
      dataClassArrayMemberHashCodeSymbol, dataClassArrayMemberToStringSymbol,
    ) + lessFunByOperandType.values + lessOrEqualFunByOperandType.values + greaterFunByOperandType.values +
        greaterOrEqualFunByOperandType.values + ieee754equalsFunByOperandType.values
  }

  private fun origin(name: String?): IrStatementOrigin? = name?.let(::IrStatementOriginImpl)

  private var temporaries = 0

  private fun declarationOrigin(name: String?): IrDeclarationOrigin = when (name) {
    "IR_TEMPORARY_VARIABLE", TExpr.Variable.TEMPORARY_ORIGIN -> IrDeclarationOrigin.IR_TEMPORARY_VARIABLE
    "FOR_LOOP_ITERATOR" -> IrDeclarationOrigin.FOR_LOOP_ITERATOR
    "FOR_LOOP_VARIABLE" -> IrDeclarationOrigin.FOR_LOOP_VARIABLE
    "FOR_LOOP_IMPLICIT_VARIABLE" -> IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE
    "CATCH_PARAMETER" -> IrDeclarationOrigin.CATCH_PARAMETER
    else -> IrDeclarationOrigin.DEFINED
  }

  // ------------------------------------------------------------------------------------------------ values

  private fun value(ref: TValueRef): IrValueDeclaration = when (ref) {
    TValueRef.Receiver -> receiverParameter ?: fail("receiver is not available")
    is TValueRef.Parameter -> parameterMapping.getOrNull(ref.index) ?: fail("parameter #${ref.index} is not available")
    is TValueRef.Local -> locals[ref.id] ?: fail("local value #${ref.id} is used before its declaration")
  }

  private fun getValue(ref: TValueRef): IrExpression {
    if (ref is TValueRef.Parameter) {
      val parameter = template.parameters.getOrNull(ref.index) ?: fail("unknown parameter #${ref.index}")
      if (parameter.isSplice) return spliceConstant(parameter)
    }
    val declaration = value(ref)
    return IrGetValueImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, declaration.type, declaration.symbol)
  }

  private fun spliceConstant(parameter: TParameter): IrExpression {
    val type = type(parameter.type)
    val o = SYNTHETIC_OFFSET
    return when (val v = instance.splices[parameter.name]) {
      null -> IrConstImpl.constNull(o, o, type)
      is String -> IrConstImpl.string(o, o, type, v)
      is Int -> IrConstImpl.int(o, o, type, v)
      is Long -> IrConstImpl.long(o, o, type, v)
      is Short -> IrConstImpl.short(o, o, type, v)
      is Byte -> IrConstImpl.byte(o, o, type, v)
      is Char -> IrConstImpl.char(o, o, type, v)
      is Boolean -> IrConstImpl.boolean(o, o, type, v)
      is Float -> IrConstImpl.float(o, o, type, v)
      is Double -> IrConstImpl.double(o, o, type, v)
      else -> fail("unsupported splice value for '${parameter.name}': $v")
    }
  }

  private fun constant(c: TExpr.Const): IrExpression {
    val type = type(c.type)
    val o = SYNTHETIC_OFFSET
    val v = c.value
    return when (c.kind) {
      TConstKind.NULL -> IrConstImpl.constNull(o, o, type)
      TConstKind.BOOLEAN -> IrConstImpl.boolean(o, o, type, v!!.toBooleanStrict())
      TConstKind.CHAR -> IrConstImpl.char(o, o, type, v!!.toInt().toChar())
      TConstKind.BYTE -> IrConstImpl.byte(o, o, type, v!!.toByte())
      TConstKind.SHORT -> IrConstImpl.short(o, o, type, v!!.toShort())
      TConstKind.INT -> IrConstImpl.int(o, o, type, v!!.toInt())
      TConstKind.LONG -> IrConstImpl.long(o, o, type, v!!.toLong())
      TConstKind.FLOAT -> IrConstImpl.float(o, o, type, Float.fromBits(v!!.toInt()))
      TConstKind.DOUBLE -> IrConstImpl.double(o, o, type, Double.fromBits(v!!.toLong()))
      TConstKind.STRING -> IrConstImpl.string(o, o, type, v!!)
    }
  }

  // ------------------------------------------------------------------------------------------------ statements

  private fun statement(t: TExpr): IrStatement = when (t) {
    is TExpr.Variable -> variable(t)
    else -> expr(t)
  }

  private fun variable(t: TExpr.Variable): IrVariable {
    val initializer = t.initializer?.let(::expr)
    // `val p = newInstance("a.Point")`: a `Dynamic` val takes the precise type of its initializer, so later
    // dynamic accesses (`p.getMember<Int>("x")`) can be resolved against the actual class.
    val variableType = if (t.type is TType.Dynamic && !t.isVar && initializer != null) initializer.type else type(t.type)
    // `val x = temporary(...)`: named like the temporaries of the IR builder (`tmp_0`, `tmp_1`, ...).
    val name = if (t.origin == TExpr.Variable.TEMPORARY_ORIGIN) "tmp_${temporaries++}" else t.name
    val variable = buildVariable(
      parent, SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, declarationOrigin(t.origin),
      Name.guessByFirstCharacter(name), variableType, isVar = t.isVar,
    )
    variable.initializer = initializer
    locals[t.id] = variable
    return variable
  }

  private fun expr(t: TExpr): IrExpression {
    val o = SYNTHETIC_OFFSET
    return when (t) {
      is TExpr.Const -> constant(t)
      is TExpr.GetValue -> getValue(t.ref)
      is TExpr.SetValue -> IrSetValueImpl(o, o, builtIns.unitType, value(t.ref).symbol, expr(t.value), origin(t.origin))
      is TExpr.Variable -> fail("a variable declaration is used as an expression")
      is TExpr.Call -> {
        val symbol = callable(t.callee)
        if (symbol == builtIns.booleanNotSymbol || symbol == builtIns.eqeqSymbol) {
          val arguments = t.arguments.map { it?.let(::expr) }
          foldConstantCall(symbol, arguments)?.let { return it }
          return IrCallImpl(o, o, type(t.type), symbol, typeArgumentsCount = 0, origin = origin(t.origin)).apply {
            arguments.forEachIndexed { i, arg -> this.arguments[i] = arg }
          }
        }
        IrCallImpl(o, o, type(t.type), symbol, typeArgumentsCount = t.typeArguments.size, origin = origin(t.origin)).apply {
          t.typeArguments.forEachIndexed { i, arg -> typeArguments[i] = type(arg) }
          if (arguments.size != t.arguments.size) {
            fail("call of ${symbol.owner.name} expects ${arguments.size} arguments, template has ${t.arguments.size}")
          }
          t.arguments.forEachIndexed { i, arg -> arguments[i] = arg?.let(::expr) }
        }
      }
      is TExpr.ConstructorCall -> {
        val classId = ClassId.fromString(t.classId)
        val candidates = finder.findConstructors(classId)
        val symbol = candidates.firstOrNull { IrSignatures.of(it.owner) == t.signature }
          ?: fail("cannot resolve constructor of ${t.classId} with signature ${t.signature}")
        IrConstructorCallImpl(
          o, o, type(t.type), symbol,
          typeArgumentsCount = t.typeArguments.size,
          constructorTypeArgumentsCount = t.constructorTypeArgumentsCount,
          origin = origin(t.origin),
        ).apply {
          t.typeArguments.forEachIndexed { i, arg -> typeArguments[i] = type(arg) }
          t.arguments.forEachIndexed { i, arg -> arguments[i] = arg?.let(::expr) }
        }
      }
      is TExpr.GetObject -> IrGetObjectValueImpl(o, o, type(t.type), classSymbol(t.classId))
      is TExpr.GetEnumEntry -> {
        val entry = classSymbol(t.classId).owner.declarations.filterIsInstance<IrEnumEntry>()
          .firstOrNull { it.name.asString() == t.entryName }
          ?: fail("enum entry ${t.classId}.${t.entryName} not found")
        IrGetEnumValueImpl(o, o, type(t.type), entry.symbol)
      }
      is TExpr.GetField -> {
        val declarations = classSymbol(t.classId).owner.declarations
        val field = declarations.filterIsInstance<IrField>().firstOrNull { it.name.asString() == t.fieldName }
          ?: declarations.filterIsInstance<IrProperty>().firstOrNull { it.name.asString() == t.fieldName }?.backingField
          ?: fail("field ${t.classId}.${t.fieldName} not found")
        IrGetFieldImpl(o, o, field.symbol, type(t.type), t.receiver?.let(::expr))
      }
      is TExpr.ClassReference -> {
        val classType = type(t.classType)
        IrClassReferenceImpl(o, o, type(t.type), classType.classifierOrFail, classType)
      }
      is TExpr.GetClass -> IrGetClassImpl(o, o, type(t.type), expr(t.argument))
      is TExpr.Return -> {
        val target = returnTargets[t.targetId] ?: fail("unknown return target #${t.targetId}")
        IrReturnImpl(o, o, builtIns.nothingType, target, expr(t.value))
      }
      is TExpr.Block -> {
        val blockType = type(t.type)
        val materialized = t.statements.map(::statement)
        // The last statement of a value block is its value: keep it.
        val statements = if (blockType.isUnit()) tidy(materialized) else tidy(materialized.dropLast(1)) + materialized.takeLast(1)
        if (t.isComposite) {
          IrCompositeImpl(o, o, type(t.type), origin(t.origin)).also { it.statements.addAll(statements) }
        } else {
          IrBlockImpl(o, o, type(t.type), origin(t.origin), statements)
        }
      }
      is TExpr.When -> whenExpression(t)
      is TExpr.StringConcat -> IrStringConcatenationImpl(o, o, type(t.type), t.arguments.map(::expr))
      is TExpr.TypeOperator -> {
        val operator = IrTypeOperator.valueOf(t.operator)
        val operand = type(t.typeOperand)
        val argument = expr(t.argument)
        // Templates written against `Any?` / `Dynamic` need casts that are upcasts once the real types
        // are known (`p.value as NativeObject?` for a `Peer?`): drop those.
        if (operator in UPCASTABLE_OPERATORS && isStaticUpcast(argument.type, operand)) argument
        else IrTypeOperatorCallImpl(o, o, type(t.type), operator, operand, argument)
      }
      is TExpr.Loop -> {
        val loop: IrLoop =
          if (t.isDoWhile) IrDoWhileLoopImpl(o, o, builtIns.unitType, origin(t.origin))
          else IrWhileLoopImpl(o, o, builtIns.unitType, origin(t.origin))
        loop.label = t.label
        loops[t.id] = loop
        // In do-while loops the condition may see variables declared in the body.
        if (t.isDoWhile) {
          loop.body = t.body?.let(::expr)
          loop.condition = expr(t.condition)
        } else {
          loop.condition = expr(t.condition)
          loop.body = t.body?.let(::expr)
        }
        loop
      }
      is TExpr.Break -> IrBreakImpl(o, o, builtIns.nothingType, loops[t.loopId] ?: fail("unknown loop #${t.loopId}"))
      is TExpr.Continue -> IrContinueImpl(o, o, builtIns.nothingType, loops[t.loopId] ?: fail("unknown loop #${t.loopId}"))
      is TExpr.Throw -> IrThrowImpl(o, o, builtIns.nothingType, expr(t.value))
      is TExpr.Try -> IrTryImpl(
        o, o, type(t.type),
        expr(t.tryResult),
        t.catches.map { IrCatchImpl(o, o, variable(it.parameter), expr(it.result)) },
        t.finallyExpression?.let(::expr),
      )
      is TExpr.Vararg -> IrVarargImpl(
        o, o, type(t.type), type(t.elementType),
        t.elements.flatMap {
          when {
            // `*list.spread()`: the spliced expressions become elements.
            it is TExpr.Spread && (it.expression as? TExpr.Dynamic)?.op == TDynamicOp.LIST_SPREAD -> expandArguments(listOf(it))
            it is TExpr.Spread -> listOf(IrSpreadElementImpl(o, o, expr(it.expression)))
            else -> listOf(expr(it))
          }
        },
      )
      is TExpr.Spread -> fail("spread operator outside of a vararg")
      is TExpr.Lambda -> lambda(t)
      is TExpr.Dynamic -> dynamic(t)
      is TExpr.NamedArgument -> fail("named argument outside of a by-name call")
      is TExpr.TypeArguments -> fail("typeArgs(...) outside of a by-name call")
      is TExpr.ForEachProperty -> forEachProperty(t)
      is TExpr.ForEachParameter -> forEachParameter(t)
      is TExpr.MapParameters -> fail("mapParameters { } can only be used as an argument of a by-name call")
      is TExpr.ParameterInfo -> parameterInfo(t)
      is TExpr.TargetInfo -> targetInfo(t)
    }
  }

  // ------------------------------------------------------------------------------------------------ late-bound access

  private fun namedClass(fqName: String): IrClassSymbol =
    findNamedClass(fqName) ?: fail("class '$fqName' is not available in this compilation")

  private fun findNamedClass(fqName: String): IrClassSymbol? = symbols.namedClasses.getOrPut(fqName) {
    ClassNames.firstResolved(fqName) { finder.findClass(ClassId.fromString(it)) }
  }

  /** The name of a dynamic access: a string constant, or a splice (a value captured by a template lambda). */
  private fun constantString(e: TExpr): String {
    if (e is TExpr.Const && e.kind == TConstKind.STRING) return e.value!!
    // Anything that is constant now: captured values, `targetFunction.name`, `p.name`, string templates...
    return when (val value = foldConstant(expr(e))) {
      is String -> value
      else -> fail(
        "names in by-name accesses must be constant when the template is applied (literals, captured " +
            "values, targetFunction / parameter facts, or string templates of those)"
      )
    }
  }

  private fun foldConstant(e: IrExpression): Any? = when (e) {
    is IrConst -> e.value
    is IrStringConcatenation -> e.arguments.map { foldConstant(it) ?: return null }.joinToString("")
    else -> null
  }

  /** Arguments of a by-name call, before overload resolution. */
  private class CallArguments(
    val dispatch: IrExpression?,
    val extension: IrExpression?,
    val positional: List<IrExpression>,
    val named: List<Pair<String, IrExpression>>,
    val explicitTypeArguments: List<IrType>,
    val expectedType: IrType?,
  ) {
    fun render() = (positional.map { it.type.render() } + named.map { "${it.first} = ${it.second.type.render()}" }).joinToString()
  }

  /** A chosen callee with an argument (or `null` = default) for each of its parameters and inferred type arguments. */
  private class Resolved(
    val target: IrFunction,
    val arguments: List<IrExpression?>,
    val substitution: Map<IrTypeParameterSymbol, IrType>,
    val exact: Boolean,
  )

  /** The handle (FunctionRef, ExprRef, ...) spliced as [e]: a value captured by the template lambda. */
  private inline fun <reified H : Any> spliceOf(e: TExpr, what: String): H {
    val parameter = ((e as? TExpr.GetValue)?.ref as? TValueRef.Parameter)?.let { template.parameters.getOrNull(it.index) }
    return parameter?.takeIf { it.isSplice }?.let { instance.splices[it.name] } as? H
      ?: fail("a $what must be created in the plugin (boberc.ir) and captured by the template lambda")
  }

  private fun functionRefOf(e: TExpr): IrFunction = spliceOf<IrFunctionRef>(e, "FunctionRef").function

  /** A copy of a spliced expression for this position, with its values remapped. */
  private fun inserted(expression: IrExpression, remap: Map<IrValueDeclaration, IrValueDeclaration>): IrExpression {
    val copy = expression.deepCopyWithSymbols(parent)
    if (remap.isEmpty()) return copy
    val symbols = remap.entries.associate { (from, to) -> from.symbol to to }
    return copy.transform(object : IrElementTransformerVoid() {
      override fun visitGetValue(expression: IrGetValue): IrExpression {
        val replacement = symbols[expression.symbol] ?: return expression
        return IrGetValueImpl(expression.startOffset, expression.endOffset, replacement.type, replacement.symbol)
      }
    }, null)
  }

  private fun refValue(ref: IrExprRef): IrExpression =
    ref.value?.let { IrGetValueImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, it.type, it.symbol) }
      ?: inserted(ref.expression!!, ref.remap)

  /** Expands spliced lists and `mapParameters` in an argument list. */
  private fun expandArguments(arguments: List<TExpr>): List<IrExpression> = arguments.flatMap {
    when {
      it is TExpr.MapParameters -> mapParameters(it)
      it is TExpr.Dynamic && it.op == TDynamicOp.LIST_SPREAD -> spliceOf<IrExprListRef>(it.name, "ExprListRef").expressions.map { e -> inserted(e, emptyMap()) }
      it is TExpr.Spread && (it.expression as? TExpr.Dynamic)?.op == TDynamicOp.LIST_SPREAD -> expandArguments(listOf(it.expression))
      else -> listOf(expr(it))
    }
  }

  private fun explicitTypeArguments(arguments: List<TExpr>): List<IrType> =
    arguments.filterIsInstance<TExpr.TypeArguments>().flatMap { it.types }.map { spliceOf<IrTypeRef>(it, "TypeRef").type }

  /** Splice operations that need no name resolution. */
  private fun spliceOperation(t: TExpr.Dynamic): IrExpression? {
    val o = SYNTHETIC_OFFSET
    return when (t.op) {
      TDynamicOp.REF_GET -> refValue(spliceOf(t.name, "ExprRef"))
      TDynamicOp.REF_SET -> {
        val ref = spliceOf<IrExprRef>(t.name, "ExprRef")
        val value = ref.value ?: fail("only an ExprRef to a variable or a parameter can be assigned")
        IrSetValueImpl(o, o, builtIns.unitType, value.symbol, expr(t.arguments.single()), null)
      }
      TDynamicOp.LIST_RUN -> IrBlockImpl(
        o, o, builtIns.unitType, null,
        spliceOf<IrExprListRef>(t.name, "ExprListRef").expressions.map { inserted(it, emptyMap()) },
      )
      TDynamicOp.LIST_SPREAD -> fail("ExprListRef.spread() can only be used as `*list.spread()` in arguments")
      TDynamicOp.TYPE_JAVA_CLASS -> javaClassOf(spliceOf<IrTypeRef>(t.name, "TypeRef").type)
      TDynamicOp.COERCE -> {
        val target = spliceOf<IrTypeRef>(t.name, "TypeRef").type
        val value = expr(t.receiver!!)
        if (value.type.classOrNull == target.classOrNull && value.type.isMarkedNullable() == target.isMarkedNullable()) value
        else IrTypeOperatorCallImpl(o, o, target, IrTypeOperator.CAST, target, value)
      }
      TDynamicOp.FIELD_GET -> {
        val field = spliceOf<IrFieldRef>(t.name, "FieldRef").field
        IrGetFieldImpl(o, o, field.symbol, field.type, t.receiver?.let(::expr))
      }
      TDynamicOp.FIELD_SET -> {
        val field = spliceOf<IrFieldRef>(t.name, "FieldRef").field
        IrSetFieldImpl(o, o, field.symbol, t.receiver?.let(::expr), expr(t.arguments.single()), builtIns.unitType)
      }
      else -> null
    }
  }

  /**
   * `T::class.java`. A type mentioning type parameters is star-projected (a class literal cannot name them); a
   * concrete one keeps its arguments, which decide the JVM class (`Array<String>` is `[Ljava/lang/String;`).
   */
  private fun javaClassOf(type: IrType): IrExpression {
    val o = SYNTHETIC_OFFSET
    val symbol = type.classOrNull ?: fail("cannot take a class literal of ${type.render()}")
    val literalType = if (type.mentionsTypeParameter()) symbol.starProjectedType else type
    val reference = IrClassReferenceImpl(o, o, builtIns.kClassClass.typeWith(literalType), symbol, literalType)
    val getter = finder.findProperties(CallableId(FqName("kotlin.jvm"), Name.identifier("java")))
      .mapNotNull { it.owner.getter }
      .firstOrNull { getter -> getter.parameters.any { it.kind == IrParameterKind.ExtensionReceiver && it.type.classOrNull == builtIns.kClassClass } }
      ?: fail("kotlin.jvm.java is not available")
    return IrCallImpl(o, o, getter.returnType.substitute(mapOf(getter.typeParameters.single().symbol to literalType)), getter.symbol, 1).apply {
      typeArguments[0] = literalType
      arguments[0] = reference
    }
  }

  private fun IrType.mentionsTypeParameter(): Boolean {
    if (classifierOrNull is IrTypeParameterSymbol) return true
    return (this as? IrSimpleType)?.arguments?.any { (it as? IrTypeProjection)?.type?.mentionsTypeParameter() == true } == true
  }

  private fun positionalArguments(t: TExpr.Dynamic): List<IrExpression> =
    expandArguments(t.arguments.filter { it !is TExpr.NamedArgument && it !is TExpr.TypeArguments })

  private fun namedArguments(t: TExpr.Dynamic): List<Pair<String, IrExpression>> =
    t.arguments.filterIsInstance<TExpr.NamedArgument>().map { constantString(it.name) to expr(it.value) }

  /** The result type the template expects; it helps inferring type arguments, unless it carries no information. */
  private fun expectedResultType(t: TExpr.Dynamic): IrType? =
    if (t.type is TType.Dynamic) null else type(t.type).takeUnless { it.isAny() || it.isNullableAny() }

  private fun callRef(t: TExpr.Dynamic): IrExpression {
    val target = functionRefOf(t.name)
    val receiver = t.receiver?.let(::expr)
    val positional = positionalArguments(t)
    val named = namedArguments(t)
    val expected = expectedResultType(t)
    val hasDispatch = target.dispatchReceiverParameter != null
    val arguments = CallArguments(
      dispatch = if (hasDispatch) receiver else null,
      extension = if (hasDispatch) t.extensionReceiver?.let(::expr) else receiver,
      positional = positional, named = named, explicitTypeArguments = explicitTypeArguments(t.arguments), expectedType = expected,
    )
    val result = build(resolve(target.name.asString(), listOf(target), arguments))
    if (t.type !is TType.Dynamic && !isAssignable(result.type, type(t.type))) {
      fail("'${target.name}' returns ${result.type.render()}, but the template expects ${type(t.type).render()}")
    }
    return result
  }

  private fun dynamic(t: TExpr.Dynamic): IrExpression {
    if (t.op == TDynamicOp.CALL_REF) return callRef(t)
    spliceOperation(t)?.let { return it }
    val name = constantString(t.name)
    val o = SYNTHETIC_OFFSET
    val receiver = t.receiver?.let(::expr)
    val extensionReceiver = t.extensionReceiver?.let(::expr)
    val positional = positionalArguments(t)
    val named = namedArguments(t)
    val expected = expectedResultType(t)

    fun args(dispatch: IrExpression?, extension: IrExpression?) =
      CallArguments(dispatch, extension, positional, named, t.typeArguments.map(::type) + explicitTypeArguments(t.arguments), expected)

    val result: IrExpression = when (t.op) {
      TDynamicOp.GET_MEMBER, TDynamicOp.SET_MEMBER -> {
        val owner = receiverClass(receiver!!, name)
        val properties = owner.declarations.filterIsInstance<IrProperty>().filter { it.name.asString() == name }
        if (properties.isEmpty()) {
          fail("class ${owner.kotlinFqNameOrUnknown()} has no property '$name' (properties: ${owner.declarations.filterIsInstance<IrProperty>().map { it.name }})")
        }
        val isSet = t.op == TDynamicOp.SET_MEMBER
        val accessors = properties.mapNotNull { if (isSet) it.setter else it.getter }
        // A `@JvmField` has no accessors on the JVM: access the field, as Kotlin does.
        val jvmField = properties.singleOrNull()?.let { (it.resolveFakeOverride() ?: it).backingField }
          ?.takeIf { it.hasAnnotation(JVM_FIELD) && extensionReceiver == null }
        if (jvmField != null) {
          checkAccessible(properties.single(), owner, "property '$name'")
          if (isSet) IrSetFieldImpl(o, o, jvmField.symbol, receiver, positional.single(), builtIns.unitType)
          else IrGetFieldImpl(o, o, jvmField.symbol, jvmField.type, receiver)
        } else if (accessors.isEmpty()) {
          val property = properties.first()
          val field = property.backingField
          if (isSet || field == null) fail("property '$name' of ${owner.kotlinFqNameOrUnknown()} is not ${if (isSet) "a `var`" else "readable"}")
          checkAccessible(property, owner, "property '$name'")
          IrGetFieldImpl(o, o, field.symbol, field.type, receiver)
        } else {
          // A setter's value is its (single) regular argument.
          build(resolve("${owner.name}.$name", accessors, args(receiver, extensionReceiver)))
        }
      }
      TDynamicOp.CALL_MEMBER -> {
        val owner = receiverClass(receiver!!, name)
        val candidates = owner.declarations.filterIsInstance<IrSimpleFunction>().filter { it.name.asString() == name }
        build(resolve("${owner.name}.$name", candidates, args(receiver, extensionReceiver)))
      }
      TDynamicOp.CALL_FUNCTION -> {
        val candidates = (staticCallableIds(name).flatMap { finder.findFunctions(it) }.map { it.owner } +
            irOnlyStatics(name)).distinct()
        // For top-level extensions, the template receiver is the extension receiver.
        build(resolve(name, candidates, args(null, receiver)))
      }
      TDynamicOp.GET_PROPERTY -> {
        val getters = staticCallableIds(name).flatMap { finder.findProperties(it) }.mapNotNull { it.owner.getter }
        build(resolve(name, getters, args(null, receiver)))
      }
      TDynamicOp.NEW_INSTANCE -> {
        val irClass = namedClass(name).owner
        build(resolve(name, irClass.declarations.filterIsInstance<IrConstructor>(), args(null, null)))
      }
      TDynamicOp.GET_OBJECT -> {
        val irClass = namedClass(name).owner
        if (irClass.kind != ClassKind.OBJECT) fail("'$name' is not an object")
        IrGetObjectValueImpl(o, o, irClass.defaultType, irClass.symbol)
      }
      TDynamicOp.IS_INSTANCE -> IrTypeOperatorCallImpl(
        o, o, builtIns.booleanType, IrTypeOperator.INSTANCEOF, namedClass(name).starProjectedType, receiver!!,
      )
      TDynamicOp.CAST -> {
        val classType = namedClass(name).starProjectedType
        IrTypeOperatorCallImpl(o, o, classType, IrTypeOperator.CAST, classType, receiver!!)
      }
      TDynamicOp.CALL_REF, TDynamicOp.REF_GET, TDynamicOp.REF_SET, TDynamicOp.LIST_SPREAD, TDynamicOp.LIST_RUN,
      TDynamicOp.TYPE_JAVA_CLASS, TDynamicOp.COERCE, TDynamicOp.FIELD_GET, TDynamicOp.FIELD_SET,
        -> error("handled before resolution")
    }
    // The template was type-checked against the type it expects (`getMember<Int>`): enforce it.
    if (t.type !is TType.Dynamic && t.op != TDynamicOp.SET_MEMBER && !isAssignable(result.type, type(t.type))) {
      fail("'$name' has type ${result.type.render()}, but the template expects ${type(t.type).render()}")
    }
    return result
  }

  private fun receiverClass(receiver: IrExpression, name: String): IrClass {
    val symbol = receiver.type.classOrNull ?: fail(
      "cannot access member '$name' on a value of type ${receiver.type.render()}: its class is unknown; " +
          "give it a mirror or `This` type, or use castTo(\"...\")"
    )
    return symbol.owner
  }

  private fun isAccessible(member: IrDeclarationWithVisibility, owner: IrClass): Boolean =
    member.visibility != DescriptorVisibilities.PRIVATE || owner == ownerClass

  private fun checkAccessible(member: IrDeclarationWithVisibility, owner: IrClass, what: String) {
    if (!isAccessible(member, owner)) fail("$what is private in ${owner.name} and not accessible from '${function.name}'")
  }

  /**
   * Static functions of a class of this compilation that exist only in IR (added by an IR extension, e.g. the
   * plugin's own helpers): the frontend-based finder can't see them, but the class's IR declarations can.
   */
  private fun irOnlyStatics(fqName: String): List<IrSimpleFunction> {
    val container = fqName.substringBeforeLast('.', missingDelimiterValue = "").ifEmpty { return emptyList() }
    val callable = fqName.substringAfterLast('.')
    val irClass = findNamedClass(container)?.owner ?: return emptyList()
    return irClass.declarations.filterIsInstance<IrSimpleFunction>()
      .filter { it.name.asString() == callable && it.dispatchReceiverParameter == null }
  }

  /** `a.b.C.f` may be a top-level function `f` in package `a.b.C` or a static member of class `a.b.C`. */
  private fun staticCallableIds(fqName: String): List<CallableId> {
    val container = fqName.substringBeforeLast('.', missingDelimiterValue = "")
    val callable = Name.identifier(fqName.substringAfterLast('.'))
    return listOf(CallableId(FqName(container), callable)) +
        if (container.isEmpty()) emptyList()
        else ClassNames.classIdCandidates(container).map { CallableId(ClassId.fromString(it), callable) }
  }

  // ---------------------------------------------------------------- overload resolution

  private fun resolve(description: String, candidates: List<IrFunction>, args: CallArguments): Resolved {
    val applicable = candidates.mapNotNull { tryResolve(it, args) }
    val chosen = applicable.singleOrNull()
      ?: applicable.filter { it.exact }.singleOrNull()
      ?: fail(
        if (applicable.isEmpty()) {
          "no '$description' accepting (${args.render()})" +
              (if (args.extension != null) " with extension receiver ${args.extension.type.render()}" else "") +
              "; candidates: ${candidates.map { c -> c.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }.map { "${it.name}: ${it.type.render()}" } }}"
        } else {
          "ambiguous call of '$description' with (${args.render()})"
        }
      )
    val owner = chosen.target.parent as? IrClass
    if (owner != null) {
      checkAccessible(chosen.target, owner, if (chosen.target is IrConstructor) "constructor of '$description'" else "'$description'")
    }
    return chosen
  }

  private fun tryResolve(target: IrFunction, args: CallArguments): Resolved? {
    val parameters = target.parameters
    if (parameters.any { it.kind == IrParameterKind.Context }) return null
    val dispatch = parameters.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }
    val extension = parameters.indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }
    if ((extension >= 0) != (args.extension != null)) return null
    if (dispatch >= 0 && args.dispatch == null) return null

    // ---- map arguments to parameters
    val assigned = arrayOfNulls<IrExpression>(parameters.size)
    if (dispatch >= 0) assigned[dispatch] = args.dispatch
    if (extension >= 0) assigned[extension] = args.extension
    val regular = parameters.withIndex().filter { it.value.kind == IrParameterKind.Regular }
    val varargElements = mutableListOf<IrExpression>()
    var varargIndex = -1
    var next = 0
    for (argument in args.positional) {
      val slot = regular.getOrNull(next) ?: return null
      if (slot.value.varargElementType != null && argument is IrVararg && varargElements.isEmpty()) {
        assigned[slot.index] = argument // an already built vararg (e.g. from a mirror call)
        next++
      } else if (slot.value.varargElementType != null) {
        varargIndex = slot.index
        varargElements += argument // the vararg absorbs all remaining positional arguments
      } else {
        assigned[slot.index] = argument
        next++
      }
    }
    for ((argumentName, argument) in args.named) {
      val slot = regular.firstOrNull { it.value.name.asString() == argumentName } ?: return null
      if (assigned[slot.index] != null || slot.index == varargIndex) return null
      assigned[slot.index] = argument
    }
    for ((index, parameter) in regular) {
      if (assigned[index] == null && index != varargIndex && parameter.varargElementType == null && !parameter.hasDefaultValue()) {
        return null
      }
    }

    // ---- infer type arguments
    val ownTypeParameters = target.typeParameters.map { it.symbol }
    val classTypeParameters = when {
      target is IrConstructor -> target.constructedClass.typeParameters.map { it.symbol }
      dispatch >= 0 -> (target.parent as? IrClass)?.typeParameters.orEmpty().map { it.symbol }
      else -> emptyList()
    }
    val variables = (classTypeParameters + ownTypeParameters).toSet()
    val bindings = HashMap<IrTypeParameterSymbol, IrType>()
    if (args.explicitTypeArguments.size == ownTypeParameters.size) {
      ownTypeParameters.zip(args.explicitTypeArguments).forEach { (p, a) -> bindings[p] = a }
    }
    parameters.forEachIndexed { index, parameter ->
      val argument = assigned[index] ?: return@forEachIndexed
      unify(parameter.type, argument.type, variables, bindings)
    }
    if (varargIndex >= 0) {
      val elementType = parameters[varargIndex].varargElementType!!
      varargElements.forEach { unify(elementType, it.type, variables, bindings) }
    }
    val resultType = if (target is IrConstructor) target.constructedClass.defaultType else target.returnType
    if (args.expectedType != null) unify(resultType, args.expectedType, variables, bindings)
    for (variable in variables) {
      if (variable !in bindings) {
        val bound = variable.owner.superTypes.firstOrNull()?.takeIf { it.classifierOrNull !is IrTypeParameterSymbol }
        bindings[variable] = bound ?: builtIns.anyNType
      }
    }

    // ---- check argument types against the substituted parameter types
    var exact = true
    parameters.forEachIndexed { index, parameter ->
      if (parameter.kind == IrParameterKind.DispatchReceiver) return@forEachIndexed
      val argument = assigned[index] ?: return@forEachIndexed
      val expectedType = parameter.type.substitute(bindings)
      if (!isAssignable(argument.type, expectedType)) return null
      if (argument.type.classOrNull != expectedType.classOrNull) exact = false
    }
    if (varargIndex >= 0) {
      val elementType = parameters[varargIndex].varargElementType!!.substitute(bindings)
      if (varargElements.any { !isAssignable(it.type, elementType) }) return null
      assigned[varargIndex] = IrVarargImpl(
        SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, parameters[varargIndex].type.substitute(bindings), elementType, varargElements,
      )
    }
    return Resolved(target, assigned.toList(), bindings, exact)
  }

  /**
   * Binds type parameters in [parameter] (a declared type) from [argument] (an actual type), structurally:
   * `Box<T>` vs `Box<Int>` binds `T = Int`; a subtype argument is first viewed as the parameter's class
   * (`IntBox : Box<Int>`).
   */
  private fun unify(parameter: IrType, argument: IrType, variables: Set<IrTypeParameterSymbol>, bindings: MutableMap<IrTypeParameterSymbol, IrType>) {
    val classifier = parameter.classifierOrNull
    if (classifier is IrTypeParameterSymbol) {
      if (classifier in variables && classifier !in bindings && !argument.isNothing()) {
        bindings[classifier] = if (parameter.isMarkedNullable()) argument.makeNotNull() else argument
      }
      return
    }
    if (classifier !is IrClassSymbol || parameter !is IrSimpleType || argument !is IrSimpleType) return
    val view = asSupertype(argument, classifier, depth = 0) ?: return
    parameter.arguments.zip(view.arguments).forEach { (p, a) ->
      val pType = (p as? IrTypeProjection)?.type ?: return@forEach
      val aType = (a as? IrTypeProjection)?.type ?: return@forEach
      unify(pType, aType, variables, bindings)
    }
  }

  private fun asSupertype(type: IrSimpleType, target: IrClassSymbol, depth: Int): IrSimpleType? {
    if (type.classifier == target) return type
    if (depth > 16) return null
    val irClass = type.classOrNull?.owner ?: return null
    val substitution = irClass.typeParameters.zip(type.arguments).associate { (parameter, argument) ->
      parameter.symbol to ((argument as? IrTypeProjection)?.type ?: builtIns.anyNType)
    }
    return irClass.superTypes.firstNotNullOfOrNull { superType ->
      (superType.substitute(substitution) as? IrSimpleType)?.let { asSupertype(it, target, depth + 1) }
    }
  }


  private fun build(resolved: Resolved): IrExpression {
    val o = SYNTHETIC_OFFSET
    val target = resolved.target
    val substitution = resolved.substitution
    return when (target) {
      is IrConstructor -> {
        val irClass = target.constructedClass
        val classArguments = irClass.typeParameters.map { substitution.getValue(it.symbol) }
        val ownArguments = target.typeParameters.map { substitution.getValue(it.symbol) }
        IrConstructorCallImpl(
          o, o, irClass.symbol.typeWith(classArguments), target.symbol,
          typeArgumentsCount = classArguments.size + ownArguments.size,
          constructorTypeArgumentsCount = ownArguments.size,
        ).apply {
          (classArguments + ownArguments).forEachIndexed { i, type -> typeArguments[i] = type }
          resolved.arguments.forEachIndexed { i, argument -> arguments[i] = argument }
        }
      }
      is IrSimpleFunction -> IrCallImpl(
        o, o, target.returnType.substitute(substitution), target.symbol, typeArgumentsCount = target.typeParameters.size,
      ).apply {
        target.typeParameters.forEachIndexed { i, parameter -> typeArguments[i] = substitution.getValue(parameter.symbol) }
        resolved.arguments.forEachIndexed { i, argument -> arguments[i] = argument }
      }
    }
  }

  // ---------------------------------------------------------------- compile-time loops

  /** Unrolls `receiver.forEachProperty { name, value -> body }` once per (accessible) property. */
  private fun forEachProperty(t: TExpr.ForEachProperty): IrExpression {
    val o = SYNTHETIC_OFFSET
    val receiver = expr(t.receiver)
    val receiverVariable = buildVariable(
      parent, o, o, IrDeclarationOrigin.IR_TEMPORARY_VARIABLE, Name.identifier("tmp_forEachProperty"), receiver.type,
    ).apply { initializer = receiver }
    val owner = receiverClass(receiver, "forEachProperty")
    val properties = owner.declarations.filterIsInstance<IrProperty>().filter { property ->
      val getter = property.getter
      getter != null && getter.parameters.none { it.kind == IrParameterKind.ExtensionReceiver } && isAccessible(property, owner)
    }
    val statements = mutableListOf<IrStatement>(receiverVariable)
    for (property in properties) {
      val getter = property.getter!!
      val read = build(
        resolve(
          "${owner.name}.${property.name}", listOf(getter),
          CallArguments(IrGetValueImpl(o, o, receiverVariable.symbol), null, emptyList(), emptyList(), emptyList(), null),
        )
      )
      val nameVariable = buildVariable(parent, o, o, IrDeclarationOrigin.DEFINED, Name.identifier(t.nameVariable), builtIns.stringType)
        .apply { initializer = IrConstImpl.string(o, o, builtIns.stringType, property.name.asString()) }
      val valueVariable = buildVariable(parent, o, o, IrDeclarationOrigin.DEFINED, Name.identifier(t.valueVariable), read.type)
        .apply { initializer = read }
      locals[t.nameLocal] = nameVariable
      locals[t.valueLocal] = valueVariable
      statements += IrBlockImpl(o, o, builtIns.unitType, null, listOf(nameVariable, valueVariable) + t.body.map(::statement))
    }
    return IrBlockImpl(o, o, builtIns.unitType, null, statements)
  }

  /**
   * Removes what the unrolling and pruning leave behind: pure `Unit` statements and empty statement blocks, and
   * flattens statement blocks that declare no variables (their scope is irrelevant).
   */
  private fun tidy(statements: List<IrStatement>): List<IrStatement> {
    val flat = statements.flatMap { statement ->
      when {
        statement is IrGetObjectValue && statement.type.isUnit() -> emptyList()
        statement is IrBlock && statement !is IrReturnableBlock && statement.origin == null && statement.type.isUnit() &&
            statement.statements.none { it is IrVariable } -> tidy(statement.statements)
        else -> listOf(statement)
      }
    }
    // `val x = e; return x` -> `return e` (typical after a loop or an `if` was pruned away): nothing can
    // refer to `x` before its declaration, and nothing after the `return` runs.
    val result = mutableListOf<IrStatement>()
    var i = 0
    while (i < flat.size) {
      val variable = flat[i] as? IrVariable
      val next = flat.getOrNull(i + 1) as? IrReturn
      val initializer = variable?.initializer
      if (variable != null && !variable.isVar && initializer != null && next != null &&
        (next.value as? IrGetValue)?.symbol == variable.symbol
      ) {
        result += IrReturnImpl(next.startOffset, next.endOffset, next.type, next.returnTargetSymbol, initializer)
        i += 2
      } else {
        result += flat[i]
        i++
      }
    }
    return result
  }

  /** A cast that cannot fail: the argument's type is already a subtype of the target type. */
  private fun isStaticUpcast(from: IrType, to: IrType): Boolean {
    if (to.classOrNull == null || from.classOrNull == null) return false
    // The compiler's own subtyping, type arguments included (`Class<Player>` <: `Class<out SharedObject>`).
    return from.isSubtypeOf(to, typeSystem)
  }

  private val typeSystem by lazy { IrTypeSystemContextImpl(builtIns) }

  /**
   * Branches whose condition is a constant when the template is applied (e.g. `p.hasAnnotation("...")`) are
   * resolved now: false branches are dropped without being materialized, so they may contain code that would
   * not resolve for this function; a true branch ends the `when`.
   */
  private fun whenExpression(t: TExpr.When): IrExpression {
    val o = SYNTHETIC_OFFSET
    val branches = mutableListOf<IrBranch>()
    for (branch in t.branches) {
      val condition = expr(branch.condition)
      val constant = (condition as? IrConst)?.value as? Boolean
      when {
        constant == false -> continue
        constant == true -> {
          if (branches.isEmpty()) return expr(branch.result)
          branches += IrElseBranchImpl(o, o, condition, expr(branch.result))
          break
        }
        branch.isElse -> { branches += IrElseBranchImpl(o, o, condition, expr(branch.result)); break }
        else -> branches += IrBranchImpl(o, o, condition, expr(branch.result))
      }
    }
    if (branches.isEmpty()) return IrBlockImpl(o, o, builtIns.unitType, null, emptyList())
    // `if (c) a.get<Dynamic>() else b.get<Dynamic>()` is `Any?` in the template; when every branch has the same
    // real type, that is the type (so a primitive is not boxed).
    // `Nothing` branches (`null`, `throw`) carry no type, except that `null` makes the result nullable.
    val resultTypes = branches.map { it.result.type }
    val informative = resultTypes.filterNot { it.isNothing() || it.isNullableNothing() }.map { it.makeNotNull() }.distinct()
    val nullable = resultTypes.any { it.isMarkedNullable() }
    val whenType = if (t.type is TType.Dynamic && informative.size == 1) {
      informative.single().let { if (nullable) it.makeNullable() else it }
    } else {
      type(t.type)
    }
    return IrWhenImpl(o, o, whenType, origin(t.origin), branches)
  }

  /** Folds `!const` and `const == const`, so compile-time conditions compose. */
  private fun foldConstantCall(symbol: IrSimpleFunctionSymbol, arguments: List<IrExpression?>): IrExpression? {
    val o = SYNTHETIC_OFFSET
    val constants = arguments.map { (it as? IrConst)?.value ?: return null }
    return when {
      symbol == builtIns.booleanNotSymbol -> IrConstImpl.boolean(o, o, builtIns.booleanType, !(constants.single() as Boolean))
      symbol == builtIns.eqeqSymbol && constants.size == 2 -> IrConstImpl.boolean(o, o, builtIns.booleanType, constants[0] == constants[1])
      else -> null
    }
  }

  // ---------------------------------------------------------------- parameters of the function the template is applied to

  /** `p` of the enclosing forEachParameter / mapParameters -> the parameter it currently stands for. */
  private val parameterBindings = HashMap<Int, Pair<Int, IrValueParameter>>()

  private fun forEachParameterOf(local: Int, action: () -> Unit) {
    regularParameters.forEachIndexed { index, parameter ->
      parameterBindings[local] = index to parameter
      action()
    }
    parameterBindings.remove(local)
  }

  private fun forEachParameter(t: TExpr.ForEachParameter): IrExpression {
    val o = SYNTHETIC_OFFSET
    val blocks = mutableListOf<IrStatement>()
    forEachParameterOf(t.parameterLocal) {
      blocks += IrBlockImpl(o, o, builtIns.unitType, null, t.body.map(::statement))
    }
    return IrBlockImpl(o, o, builtIns.unitType, null, blocks)
  }

  private fun mapParameters(t: TExpr.MapParameters): List<IrExpression> {
    val o = SYNTHETIC_OFFSET
    val results = mutableListOf<IrExpression>()
    forEachParameterOf(t.parameterLocal) {
      val statements = t.statements.map(::statement)
      val result = expr(t.result)
      results += if (statements.isEmpty()) result else IrBlockImpl(o, o, result.type, null, statements + result)
    }
    return results
  }

  private fun targetInfo(t: TExpr.TargetInfo): IrExpression {
    val o = SYNTHETIC_OFFSET
    return when (t.op) {
      TTargetInfo.NAME -> IrConstImpl.string(o, o, builtIns.stringType, function.name.asString())
      TTargetInfo.PARAMETER_COUNT -> IrConstImpl.int(o, o, builtIns.intType, regularParameters.size)
      TTargetInfo.HAS_ANNOTATION -> IrConstImpl.boolean(
        o, o, builtIns.booleanType, function.hasAnnotation(FqName(constantString(t.argument!!))),
      )
    }
  }

  private fun parameterInfo(t: TExpr.ParameterInfo): IrExpression {
    val o = SYNTHETIC_OFFSET
    val (index, parameter) = parameterBindings[t.parameterLocal]
      ?: fail("a TemplateParameter is used outside of its forEachParameter / mapParameters block")
    return when (t.op) {
      TParameterInfo.NAME -> IrConstImpl.string(o, o, builtIns.stringType, parameter.name.asString())
      TParameterInfo.INDEX -> IrConstImpl.int(o, o, builtIns.intType, index)
      TParameterInfo.VALUE -> IrGetValueImpl(o, o, parameter.type, parameter.symbol)
      TParameterInfo.HAS_ANNOTATION -> IrConstImpl.boolean(
        o, o, builtIns.booleanType, parameter.hasAnnotation(FqName(constantString(t.argument!!))),
      )
      TParameterInfo.IS_SUBTYPE_OF -> {
        val expected = namedClass(constantString(t.argument!!))
        val actual = parameter.type.classOrNull
        IrConstImpl.boolean(o, o, builtIns.booleanType, actual == expected || parameter.type.isSubtypeOfClass(expected))
      }
    }
  }

  private fun lambda(t: TExpr.Lambda): IrExpression {
    val lambda = factory.buildFun {
      startOffset = SYNTHETIC_OFFSET
      endOffset = SYNTHETIC_OFFSET
      origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
      name = SpecialNames.ANONYMOUS
      visibility = DescriptorVisibilities.LOCAL
      returnType = type(t.returnType)
      isSuspend = t.isSuspend
    }
    lambda.parent = parent
    for (p in t.parameters) {
      val parameter = buildValueParameter(lambda) {
        name = Name.guessByFirstCharacter(p.name)
        type = type(p.type)
        kind = when (p.kind) {
          TExpr.LambdaParameterKind.EXTENSION_RECEIVER -> IrParameterKind.ExtensionReceiver
          TExpr.LambdaParameterKind.REGULAR -> IrParameterKind.Regular
        }
      }
      lambda.parameters += parameter
      locals[p.id] = parameter
    }
    returnTargets[t.id] = lambda.symbol
    val savedParent = parent
    parent = lambda
    try {
      lambda.body = factory.createBlockBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, tidy(t.body.map(::statement)))
    } finally {
      parent = savedParent
    }
    return IrFunctionExpressionImpl(
      SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type(t.type), lambda, origin(t.origin) ?: IrStatementOrigin.LAMBDA,
    )
  }
}

private val JVM_FIELD = FqName("kotlin.jvm.JvmField")

private val UPCASTABLE_OPERATORS = setOf(IrTypeOperator.CAST, IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.SAFE_CAST)

private fun IrDeclarationParent.kotlinFqNameOrUnknown(): String = runCatching { kotlinFqName.asString() }.getOrDefault("<unknown>")
