package boberc.compiler.ir

import boberc.ir.IrSignatures
import boberc.model.*
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.ClassId

/** Reported as a compilation error at [element]. */
class TemplateEncodingException(val element: IrElement, message: String) : Exception(message)

/**
 * Captures a fully resolved `@Template` function (signature + body IR) into a [FunctionTemplate].
 *
 * Every reference to a declaration is converted into a symbolic one (class id / callable id + signature), so
 * the result can be re-materialized in another compilation by `boberc.ir.IrTemplates`.
 */
class TemplateEncoder private constructor(private val function: IrSimpleFunction, private val isLambda: Boolean) {
    /** Encodes a `@Template` function. */
    constructor(function: IrSimpleFunction) : this(function, isLambda = false)

    private val receiverParameter = function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
    private val regularParameters = function.parameters.filter { it.kind == IrParameterKind.Regular }
    private val parameterIndex = regularParameters.withIndex().associate { (i, p) -> p.symbol to i }
    private val parameters = mutableListOf<TParameter>()

    /** Values captured by a template lambda, in the order of the splice parameters they became. */
    private val captured = LinkedHashMap<IrValueSymbol, Int>()
    val capturedValues: List<IrValueSymbol> get() = captured.keys.toList()

    private val localIds = HashMap<IrValueSymbol, Int>()
    private val returnTargetIds = HashMap<IrReturnTargetSymbol, Int>()
    private val loopIds = HashMap<IrLoop, Int>()
    private var nextId = 1

    private fun fail(element: IrElement, message: String): Nothing = throw TemplateEncodingException(element, message)

    fun encode(): FunctionTemplate {
        validateDeclaration()
        returnTargetIds[function.symbol] = 0
        regularParameters.mapTo(parameters, ::parameter)
        val body = when (val body = function.body) {
            is IrBlockBody -> TExpr.Block(body.statements.map(::statement), UNIT, origin = null, isComposite = false)
            is IrExpressionBody -> TExpr.Block(listOf(TExpr.Return(0, expr(body.expression))), UNIT, origin = null, isComposite = false)
            else -> fail(function, "template '${function.name}' must have a body")
        }
        if (isLambda) {
            return FunctionTemplate(
                name = "lambda",
                visibility = TVisibility.PUBLIC,
                isOperator = false,
                isInfix = false,
                typeParameters = emptyList(),
                receiverType = null,
                parameters = parameters.toList(),
                returnType = type(function.returnType, function),
                body = body,
                isLambda = true,
            )
        }
        return FunctionTemplate(
            name = function.name.asString(),
            visibility = types.visibility(function.visibility),
            isOperator = function.isOperator,
            isInfix = function.isInfix,
            typeParameters = function.typeParameters.map(types::typeParameter),
            receiverType = receiverParameter?.let { type(it.type, it) },
            parameters = parameters.toList(),
            returnType = type(function.returnType, function),
            body = body,
        )
    }

    private fun validateDeclaration() {
        if (isLambda) {
            if (receiverParameter != null || function.parameters.any { it.kind != IrParameterKind.Regular }) {
                fail(function, "template lambdas with receivers")
            }
            return
        }
        if (function.parent !is IrFile) fail(function, "templates must be top-level functions")
        if (function.isSuspend) fail(function, "suspend templates")
        if (function.isInline) fail(function, "inline templates")
        if (function.isExpect) fail(function, "expect templates")
        if (function.parameters.any { it.kind == IrParameterKind.Context }) fail(function, "context parameters")
    }

    private fun parameter(p: IrValueParameter): TParameter {
        val isSplice = p.hasAnnotation(BobercNames.SPLICE_ANNOTATION)
        val type = type(p.type, p)
        if (isSplice) {
            val classId = (type as? TType.Class)?.classId
            if (classId !in BobercNames.SPLICEABLE_TYPES || p.varargElementType != null) {
                fail(p, "splice parameter '${p.name}' must have a primitive, String or boberc handle type")
            }
            if (p.defaultValue != null) fail(p, "splice parameter '${p.name}' cannot have a default value")
        }
        return TParameter(
            name = p.name.asString(),
            type = type,
            isSplice = isSplice,
            varargElementType = p.varargElementType?.let { type(it, p) },
            isCrossinline = p.isCrossinline,
            isNoinline = p.isNoinline,
            defaultValue = p.defaultValue?.expression?.let(::expr),
        )
    }

    // ------------------------------------------------------------------------------------------------ types

    private val types = TypeEncoder(function.typeParameters, "the template") { at, message -> fail(at, message) }

    private fun type(type: IrType, at: IrElement): TType = types.type(type, at)


    private fun checkAvailableInTarget(declaration: IrDeclaration, at: IrElement) = types.checkAvailableInTarget(declaration, at)

    // ------------------------------------------------------------------------------------------------ values

    private fun ref(symbol: IrValueSymbol, at: IrElement): TValueRef {
        val owner = symbol.owner
        if (symbol in templateParameterLocals) fail(at, "a TemplateParameter can only be used through its members (name, value, ...)")
        if (owner == receiverParameter) return TValueRef.Receiver
        parameterIndex[symbol]?.let { return TValueRef.Parameter(it) }
        localIds[symbol]?.let { return TValueRef.Local(it) }
        if (isLambda) return TValueRef.Parameter(capture(symbol, at))
        fail(at, "reference to '${owner.name}' declared outside of the template")
    }

    /** A value captured by a template lambda becomes a splice parameter bound to the captured value. */
    private fun capture(symbol: IrValueSymbol, at: IrElement): Int = captured.getOrPut(symbol) {
        val owner = symbol.owner
        val type = type(owner.type, at)
        if ((type as? TType.Class)?.classId !in BobercNames.SPLICEABLE_TYPES) {
            fail(
                at,
                "captured value '${owner.name}' of type ${owner.type.render()}; a template lambda can only capture " +
                        "values of primitive or String types, or boberc handles (FunctionRef, ExprRef, ...; assign the value to a local `val` first)",
            )
        }
        var name = owner.name.asString()
        while (parameters.any { it.name == name }) name += "_"
        parameters += TParameter(name, type, isSplice = true, null, isCrossinline = false, isNoinline = false, defaultValue = null)
        parameters.size - 1
    }

    // ------------------------------------------------------------------------------------------------ statements

    private fun statement(s: IrStatement): TExpr = when (s) {
        is IrVariable -> variable(s)
        is IrExpression -> expr(s)
        is IrClass -> fail(s, "local classes and objects")
        is IrFunction -> fail(s, "local functions")
        else -> fail(s, "statement ${s::class.simpleName}")
    }

    private fun variable(v: IrVariable): TExpr.Variable {
        if (v.isLateinit) fail(v, "lateinit variables")
        if (v.isConst) fail(v, "const variables")
        val id = nextId++
        localIds[v.symbol] = id
        // `val x = temporary(value)`: a compiler temporary initialized with `value`.
        val temporary = (v.initializer as? IrCall)?.takeIf { it.symbol.owner.kotlinFqName == BobercNames.TEMPORARY_INTRINSIC }
        if (temporary != null && v.isVar) fail(v, "temporary() must initialize a `val`")
        return TExpr.Variable(
            id = id,
            name = v.name.asString(),
            type = type(v.type, v),
            isVar = v.isVar,
            origin = if (temporary != null) TExpr.Variable.TEMPORARY_ORIGIN else v.origin.toString().takeIf { it != IrDeclarationOrigin.DEFINED.toString() },
            initializer = (temporary?.arguments?.get(0) ?: v.initializer)?.let(::expr),
        )
    }

    private fun expr(e: IrExpression): TExpr = when (e) {
        is IrConst -> constant(e)
        is IrGetValue -> TExpr.GetValue(ref(e.symbol, e))
        is IrSetValue -> TExpr.SetValue(ref(e.symbol, e), expr(e.value), e.origin?.debugName)
        is IrCall -> call(e)
        is IrConstructorCall -> constructorCall(e)
        is IrGetObjectValue -> {
            checkAvailableInTarget(e.symbol.owner, e)
            TExpr.GetObject(classId(e.symbol.owner, e).asString(), type(e.type, e))
        }
        is IrGetEnumValue -> {
            val enumClass = e.symbol.owner.parentAsClass
            checkAvailableInTarget(enumClass, e)
            TExpr.GetEnumEntry(classId(enumClass, e).asString(), e.symbol.owner.name.asString(), type(e.type, e))
        }
        is IrGetField -> {
            val field = e.symbol.owner
            val owner = field.parent as? IrClass ?: fail(e, "field access outside of a class")
            checkAvailableInTarget(owner, e)
            TExpr.GetField(classId(owner, e).asString(), field.name.asString(), e.receiver?.let(::expr), type(e.type, e))
        }
        is IrClassReference -> TExpr.ClassReference(type(e.classType, e), type(e.type, e))
        is IrGetClass -> TExpr.GetClass(expr(e.argument), type(e.type, e))
        is IrReturn -> {
            if (e.returnTargetSymbol in unrolledLambdas) {
                fail(e, "return@forEachProperty (the block is unrolled once per property); use if/else instead")
            }
            val target = returnTargetIds[e.returnTargetSymbol] ?: fail(e, "non-local return")
            TExpr.Return(target, expr(e.value))
        }
        is IrReturnableBlock -> fail(e, "inlined blocks")
        is IrComposite -> TExpr.Block(e.statements.map(::statement), type(e.type, e), e.origin?.debugName, isComposite = true)
        is IrBlock -> TExpr.Block(e.statements.map(::statement), type(e.type, e), e.origin?.debugName, isComposite = false)
        is IrWhen -> TExpr.When(
            e.branches.map { TExpr.Branch(expr(it.condition), expr(it.result), isElse = it is IrElseBranch) },
            type(e.type, e), e.origin?.debugName,
        )
        is IrStringConcatenation -> TExpr.StringConcat(e.arguments.map(::expr), type(e.type, e))
        is IrTypeOperatorCall -> TExpr.TypeOperator(e.operator.name, type(e.typeOperand, e), expr(e.argument), type(e.type, e))
        is IrLoop -> loop(e)
        is IrBreak -> TExpr.Break(loopIds[e.loop] ?: fail(e, "break outside of the template"))
        is IrContinue -> TExpr.Continue(loopIds[e.loop] ?: fail(e, "continue outside of the template"))
        is IrThrow -> TExpr.Throw(expr(e.value))
        is IrTry -> TExpr.Try(
            expr(e.tryResult),
            e.catches.map { TExpr.Catch(variable(it.catchParameter), expr(it.result)) },
            e.finallyExpression?.let(::expr),
            type(e.type, e),
        )
        is IrVararg -> TExpr.Vararg(
            e.elements.map {
                when (it) {
                    is IrSpreadElement -> TExpr.Spread(expr(it.expression))
                    is IrExpression -> expr(it)
                    else -> fail(e, "vararg element ${it::class.simpleName}")
                }
            },
            type(e.varargElementType, e), type(e.type, e),
        )
        is IrFunctionExpression -> lambda(e)
        is IrFunctionReference, is IrRichFunctionReference -> fail(e, "callable references (use a lambda instead)")
        is IrPropertyReference, is IrRichPropertyReference -> fail(e, "property references")
        else -> fail(e, "expression ${e::class.simpleName}")
    }

    private fun constant(e: IrConst): TExpr.Const {
        val type = type(e.type, e)
        return when (e.kind) {
            IrConstKind.Null -> TExpr.Const(TConstKind.NULL, null, type)
            IrConstKind.Boolean -> TExpr.Const(TConstKind.BOOLEAN, e.value.toString(), type)
            IrConstKind.Char -> TExpr.Const(TConstKind.CHAR, (e.value as Char).code.toString(), type)
            IrConstKind.Byte -> TExpr.Const(TConstKind.BYTE, e.value.toString(), type)
            IrConstKind.Short -> TExpr.Const(TConstKind.SHORT, e.value.toString(), type)
            IrConstKind.Int -> TExpr.Const(TConstKind.INT, e.value.toString(), type)
            IrConstKind.Long -> TExpr.Const(TConstKind.LONG, e.value.toString(), type)
            IrConstKind.Float -> TExpr.Const(TConstKind.FLOAT, (e.value as Float).toRawBits().toString(), type)
            IrConstKind.Double -> TExpr.Const(TConstKind.DOUBLE, (e.value as Double).toRawBits().toString(), type)
            IrConstKind.String -> TExpr.Const(TConstKind.STRING, e.value as String, type)
        }
    }

    private fun call(e: IrCall): TExpr {
        if (e.superQualifierSymbol != null) fail(e, "super calls")
        val declared = e.symbol.owner
        // Fake overrides only exist in the class hierarchy seen by this compilation; refer to the real declaration.
        val target = declared.resolveFakeOverrideMaybeAbstract() ?: declared
        if (target.kotlinFqName == BobercNames.TEMPORARY_INTRINSIC) fail(e, "temporary() is only allowed as the initializer of a local `val`")
        constantValue(target)?.let { return it }
        templateParameterAccess(e, target)?.let { return it }
        targetFunctionAccess(e, target)?.let { return it }
        dynamicIntrinsic(e, target)?.let { return it }
        mirrorAccess(e, target)?.let { return it }
        if (target.symbol.owner.hasAnnotation(BobercNames.TEMPLATE_ANNOTATION)) {
            fail(e, "calls to other templates")
        }
        // Arguments first: problems with captured receivers are more precise than with the callee.
        val arguments = e.arguments.map { it?.let(::expr) }
        return TExpr.Call(
            callee = callee(target, e),
            typeArguments = e.typeArguments.map { type(it ?: fail(e, "call without inferred type arguments"), e) },
            arguments = arguments,
            type = type(e.type, e),
            origin = e.origin?.debugName,
        )
    }

    /** `""` for an unnamed mirror (bound to the owner class), the class name for a named one, `null` otherwise. */
    private fun IrClass.mirrorTarget(): String? = with(types) { mirrorTarget() }

    /** Member access on a `@Mirror` interface: resolved by name in the target compilation. */
    private fun mirrorAccess(e: IrCall, target: IrSimpleFunction): TExpr? {
        val property = target.correspondingPropertySymbol?.owner
        val mirror = (target.parent as? IrClass)?.takeIf { it.mirrorTarget() != null } ?: return null
        if (target.parameters.any { it.kind == IrParameterKind.Context }) fail(e, "context parameters in mirror ${mirror.name}")
        val receiver = e.arguments[0] ?: fail(e, "mirror access without a receiver")
        val extensionIndex = target.extensionReceiverIndex
        val extensionReceiver = if (extensionIndex >= 0) expr(e.arguments[extensionIndex]!!) else null
        val regular = target.parameters.withIndex().filter { it.value.kind == IrParameterKind.Regular }
        // All arguments given: pass them by position. Some omitted (defaults): pass the given ones by name.
        val allGiven = regular.all { e.arguments[it.index] != null }
        val arguments = regular.mapNotNull { (index, parameter) ->
            val argument = e.arguments[index] ?: return@mapNotNull null
            if (allGiven) expr(argument) else TExpr.NamedArgument(stringConst(parameter.name.asString()), expr(argument))
        }
        val (op, name) = when {
            property == null -> TDynamicOp.CALL_MEMBER to target.name
            property.getter == target -> TDynamicOp.GET_MEMBER to property.name
            else -> TDynamicOp.SET_MEMBER to property.name
        }
        return TExpr.Dynamic(
            op, stringConst(name.asString()), expr(receiver), extensionReceiver,
            typeArguments = e.typeArguments.map { type(it ?: fail(e, "mirror call without type arguments"), e) },
            arguments = arguments, type = type(e.type, e),
        )
    }

    private fun stringConst(value: String) = TExpr.Const(TConstKind.STRING, value, STRING)

    /** A `const val` (of the plugin module or a library) is inlined: its value is known now. */
    private fun constantValue(getter: IrSimpleFunction): TExpr? {
        val property = getter.correspondingPropertySymbol?.owner ?: return null
        if (!property.isConst || property.getter != getter) return null
        val initializer = property.backingField?.initializer?.expression as? IrConst ?: return null
        return constant(initializer)
    }

    /** Locals standing for a `TemplateParameter` (the lambda parameter of forEachParameter / mapParameters). */
    private val templateParameterLocals = HashMap<IrValueSymbol, Int>()

    private fun templateParameterAccess(e: IrCall, target: IrSimpleFunction): TExpr? {
        val owner = (target.correspondingPropertySymbol?.owner?.parent ?: target.parent) as? IrClass ?: return null
        if (owner.classId != BobercNames.TEMPLATE_PARAMETER_CLASS_ID) return null
        val receiver = e.arguments[0] as? IrGetValue
        val local = receiver?.let { templateParameterLocals[it.symbol] }
            ?: fail(e, "TemplateParameter members can only be used on the parameter of forEachParameter / mapParameters")
        val name = (target.correspondingPropertySymbol?.owner?.name ?: target.name).asString()
        val op = when (name) {
            "name" -> TParameterInfo.NAME
            "index" -> TParameterInfo.INDEX
            "value" -> TParameterInfo.VALUE
            "hasAnnotation" -> TParameterInfo.HAS_ANNOTATION
            "isSubtypeOf" -> TParameterInfo.IS_SUBTYPE_OF
            else -> fail(e, "TemplateParameter.$name")
        }
        return TExpr.ParameterInfo(local, op, nameArgument(e, e.arguments.getOrNull(1), "TemplateParameter.$name"))
    }

    /**
     * A class name argument: a string constant, a captured value, or a class literal (`AsNativePointer::class`,
     * encoded as its fully qualified name; the class itself is never loaded).
     */
    private fun nameArgument(at: IrElement, argument: IrExpression?, what: String): TExpr? {
        if (argument == null) return null
        if (argument is IrClassReference) {
            val irClass = (argument.symbol.owner as? IrClass) ?: fail(at, "class literal of a type parameter in $what")
            val classId = irClass.classId ?: fail(at, "class literal of a local class in $what")
            return stringConst(classId.asSingleFqName().asString())
        }
        return expr(argument) // must be constant when the template is applied (checked then)
    }

    /** `targetFunction.name`, `targetFunction.hasAnnotation(...)`, ...: compile-time facts about the target. */
    private fun targetFunctionAccess(e: IrCall, target: IrSimpleFunction): TExpr? {
        val owner = (target.correspondingPropertySymbol?.owner?.parent ?: target.parent) as? IrClass ?: return null
        if (owner.classId != BobercNames.TARGET_FUNCTION_CLASS_ID) return null
        val receiver = e.arguments[0] as? IrCall
        if (receiver?.symbol?.owner?.correspondingPropertySymbol?.owner?.fqNameWhenAvailable != BobercNames.TARGET_FUNCTION) {
            fail(e, "TargetFunction members can only be used directly on `targetFunction`")
        }
        val name = (target.correspondingPropertySymbol?.owner?.name ?: target.name).asString()
        val op = when (name) {
            "name" -> TTargetInfo.NAME
            "parameterCount" -> TTargetInfo.PARAMETER_COUNT
            "hasAnnotation" -> TTargetInfo.HAS_ANNOTATION
            else -> fail(e, "TargetFunction.$name")
        }
        return TExpr.TargetInfo(op, nameArgument(e, e.arguments.getOrNull(1), "TargetFunction.$name"))
    }

    /** The lambda of forEachParameter / mapParameters: (parameter local, statements, trailing result). */
    private fun parameterLambda(e: IrCall, functionName: String): Triple<Int, List<TExpr>, TExpr> {
        val lambda = (e.arguments.last() as? IrFunctionExpression)?.function ?: fail(e, "$functionName() needs a lambda literal")
        val parameter = lambda.parameters.single { it.kind == IrParameterKind.Regular }
        val local = nextId++.also { templateParameterLocals[parameter.symbol] = it }
        val (statements, trailingValue) = unrolled(lambda)
        val body = statements.map(::statement)
        val result = trailingValue?.let(::expr) ?: TExpr.GetObject("kotlin/Unit", UNIT)
        return Triple(local, body, result)
    }

    /**
     * The body of a lambda that is unrolled at materialization (it has no `return@lambda` target any more): its
     * statements, and the value of its trailing `return@lambda` (implicit for an expression lambda), if any.
     */
    private fun unrolled(lambda: IrSimpleFunction): Pair<List<IrStatement>, IrExpression?> {
        unrolledLambdas += lambda.symbol
        val statements = (lambda.body as? IrBlockBody)?.statements.orEmpty()
        val trailingReturn = (statements.lastOrNull() as? IrReturn)?.takeIf { it.returnTargetSymbol == lambda.symbol }
        return if (trailingReturn == null) statements to null else statements.dropLast(1) to trailingReturn.value
    }

    /** `getMember`, `newInstance`, `forEachProperty`, ... from `boberc/Dynamic.kt`. */
    private fun dynamicIntrinsic(e: IrCall, target: IrSimpleFunction): TExpr? {
        val parent = target.parent
        val inBoberc = when (parent) {
            is IrPackageFragment -> parent.packageFqName == BobercNames.BOBERC_PACKAGE
            // Library top-level functions may sit in a file facade class.
            is IrClass -> parent.origin in FILE_FACADE_ORIGINS && (parent.parent as? IrPackageFragment)?.packageFqName == BobercNames.BOBERC_PACKAGE
            else -> false
        }
        if (!inBoberc) return null
        val functionName = target.name.asString()
        if (functionName == "forEachProperty") return forEachProperty(e, target)
        if (functionName == "forEachParameter") {
            val (local, body, result) = parameterLambda(e, functionName)
            return TExpr.ForEachParameter(local, body + result)
        }
        if (functionName == "mapParameters") {
            val (local, body, result) = parameterLambda(e, functionName)
            return TExpr.MapParameters(local, body, result)
        }
        if (functionName == "mirror" || functionName == "construct") return mirrorInstance(e, functionName)
        spliceIntrinsic(e, target, functionName)?.let { return it }
        val firstRegular = target.parameters.firstOrNull { it.kind == IrParameterKind.Regular }
        val isRefCall = (functionName == "call" && target.parameters.any { it.kind == IrParameterKind.ExtensionReceiver }) ||
                (functionName == "callMember" && (firstRegular?.type?.classOrNull?.owner?.classId == BobercNames.FUNCTION_REF_CLASS_ID))
        if (isRefCall) return functionRefCall(e, target, functionName)
        val intrinsic = DYNAMIC_INTRINSICS[functionName] ?: return null
        val receiverIndex = target.extensionReceiverIndex
        val regular = target.regularIndices
        val receiver = if (receiverIndex >= 0) expr(e.arguments[receiverIndex]!!) else null
        val nameArgument = e.arguments[regular[0]]!!
        val name = expr(nameArgument)
        // Must be constant when the template is applied (checked then): a literal, a captured value,
        // `targetFunction.name`, `p.name`, or a string template of those.
        var rest = regular.drop(1)
        // callMemberExtension / getMemberExtension: the next argument is the extension receiver of the member.
        val extensionReceiver = if (intrinsic.takesMemberExtensionReceiver) expr(e.arguments[rest.first()]!!).also { rest = rest.drop(1) } else null
        val arguments = rest.flatMap { varargElements(e, e.arguments[it], functionName) }
        return TExpr.Dynamic(intrinsic.op, name, receiver, extensionReceiver, emptyList(), arguments, type(e.type, e))
    }

    /** `ref.get()`, `list.spread()`, `type.javaClass()`, `x.coerceTo(type)`, `x.getField(field)`, ... (boberc/Quotes.kt). */
    private fun spliceIntrinsic(e: IrCall, target: IrSimpleFunction, functionName: String): TExpr? {
        val extensionIndex = target.extensionReceiverIndex
        val regular = target.regularIndices
        val receiverClass = if (extensionIndex >= 0) target.parameters[extensionIndex].type.classOrNull?.owner?.classId else null
        fun handle(argument: IrExpression?): TExpr {
            val encoded = expr(argument ?: fail(e, "missing handle in $functionName()"))
            if (encoded !is TExpr.GetValue) fail(argument, "a boberc handle must be a value captured by the template lambda")
            return encoded
        }
        fun receiverHandle() = handle(e.arguments[extensionIndex])
        val type = type(e.type, e)
        fun dynamic(op: TDynamicOp, name: TExpr, receiver: TExpr? = null, arguments: List<TExpr> = emptyList()) =
            TExpr.Dynamic(op, name, receiver, null, emptyList(), arguments, type)
        return when {
            functionName == "get" && receiverClass == BobercNames.EXPR_REF_CLASS_ID -> dynamic(TDynamicOp.REF_GET, receiverHandle())
            functionName == "set" && receiverClass == BobercNames.EXPR_REF_CLASS_ID ->
                dynamic(TDynamicOp.REF_SET, receiverHandle(), arguments = listOf(expr(e.arguments[regular[0]]!!)))
            functionName == "spread" && receiverClass == BobercNames.EXPR_LIST_REF_CLASS_ID -> dynamic(TDynamicOp.LIST_SPREAD, receiverHandle())
            functionName == "run" && receiverClass == BobercNames.EXPR_LIST_REF_CLASS_ID -> dynamic(TDynamicOp.LIST_RUN, receiverHandle())
            functionName == "javaClass" && receiverClass == BobercNames.TYPE_REF_CLASS_ID -> dynamic(TDynamicOp.TYPE_JAVA_CLASS, receiverHandle())
            functionName == "get" && receiverClass == BobercNames.FIELD_REF_CLASS_ID -> dynamic(TDynamicOp.FIELD_GET, receiverHandle())
            functionName == "coerceTo" -> dynamic(TDynamicOp.COERCE, handle(e.arguments[regular[0]]), receiver = expr(e.arguments[extensionIndex]!!))
            functionName == "getField" -> dynamic(TDynamicOp.FIELD_GET, handle(e.arguments[regular[0]]), receiver = expr(e.arguments[extensionIndex]!!))
            functionName == "setField" -> {
                val receiver = e.arguments[extensionIndex]!!
                val isStatic = receiver is IrConst && receiver.value == null
                dynamic(
                    TDynamicOp.FIELD_SET, handle(e.arguments[regular[0]]), receiver = if (isStatic) null else expr(receiver),
                    arguments = listOf(expr(e.arguments[regular[1]]!!)),
                )
            }
            functionName == "typeArgs" -> fail(e, "typeArgs(...) can only be used as an argument of a by-name call")
            else -> null
        }
    }

    /** `mirror<M>()` / `construct<M>(args)`: the class comes from the named mirror `M`. */
    private fun mirrorInstance(e: IrCall, functionName: String): TExpr {
        val mirrorType = type(e.typeArguments.single() ?: fail(e, "$functionName() without a type argument"), e)
        val target = (mirrorType as? TType.Named)?.fqName
            ?: fail(e, "$functionName<...>() needs a mirror named after a class, e.g. @Mirror(\"com.example.Registry\")")
        val arguments = varargElements(e, e.arguments.getOrNull(0), functionName)
        val op = if (functionName == "mirror") TDynamicOp.GET_OBJECT else TDynamicOp.NEW_INSTANCE
        return TExpr.Dynamic(op, stringConst(target), null, null, emptyList(), arguments, mirrorType)
    }

    /** `ref.call(args)` / `receiver.callMember(ref, args)`: the function is the spliced FunctionRef. */
    private fun functionRefCall(e: IrCall, target: IrSimpleFunction, functionName: String): TExpr {
        val extensionIndex = target.extensionReceiverIndex
        val regular = target.regularIndices
        val (refArgument, receiver, varargIndex) = if (functionName == "call") {
            Triple(e.arguments[extensionIndex]!!, null, regular.getOrNull(0))
        } else {
            Triple(e.arguments[regular[0]]!!, expr(e.arguments[extensionIndex]!!), regular.getOrNull(1))
        }
        val ref = expr(refArgument)
        if (ref !is TExpr.GetValue) fail(refArgument, "a FunctionRef must be a value captured by the template lambda")
        val arguments = varargElements(e, varargIndex?.let { e.arguments[it] }, functionName)
        return TExpr.Dynamic(TDynamicOp.CALL_REF, ref, receiver, null, emptyList(), arguments, type(e.type, e))
    }

    private fun varargElements(e: IrCall, argument: IrExpression?, functionName: String): List<TExpr> = when (argument) {
        null -> emptyList()
        is IrVararg -> argument.elements.map { element -> varargElement(e, element, functionName) }
        else -> listOf(expr(argument))
    }

    /** An argument of a by-name call: a value, `arg(name, value)`, `typeArgs(...)`, or `*list.spread()`. */
    private fun varargElement(e: IrCall, element: IrVarargElement, functionName: String): TExpr = when (element) {
        is IrSpreadElement -> {
            val spread = expr(element.expression)
            if ((spread as? TExpr.Dynamic)?.op != TDynamicOp.LIST_SPREAD) fail(e, "spread arguments in $functionName() other than *list.spread()")
            spread
        }
        is IrExpression -> namedArgumentOrExpr(element)
        else -> fail(e, "vararg element ${element::class.simpleName}")
    }

    /** `arg("name", value)` / `typeArgs(...)` inside the arguments of a by-name call. */
    private fun namedArgumentOrExpr(element: IrExpression): TExpr {
        val call = element as? IrCall
        val callee = call?.symbol?.owner
        if (callee != null && callee.fqNameWhenAvailable == BobercNames.TYPE_ARGS_INTRINSIC) {
            val types = (call.arguments[0] as? IrVararg)?.elements.orEmpty().map { element ->
                val encoded = expr(element as? IrExpression ?: fail(call, "typeArgs(*...)"))
                if (encoded !is TExpr.GetValue) fail(call, "typeArgs() takes TypeRefs captured by the template lambda")
                encoded
            }
            return TExpr.TypeArguments(types)
        }
        if (callee == null || callee.fqNameWhenAvailable != BobercNames.ARG_INTRINSIC) return expr(element)
        return TExpr.NamedArgument(expr(call.arguments[0]!!), expr(call.arguments[1]!!))
    }

    /** Lambdas whose `return@label` would have no target after unrolling. */
    private val unrolledLambdas = HashSet<IrReturnTargetSymbol>()

    private fun forEachProperty(e: IrCall, target: IrSimpleFunction): TExpr {
        val receiverIndex = target.extensionReceiverIndex
        val receiver = expr(e.arguments[receiverIndex]!!)
        val lambda = (e.arguments.last() as? IrFunctionExpression)?.function
            ?: fail(e, "forEachProperty() needs a lambda literal")
        val (nameParameter, valueParameter) = lambda.parameters.filter { it.kind == IrParameterKind.Regular }
        val nameLocal = nextId++.also { localIds[nameParameter.symbol] = it }
        val valueLocal = nextId++.also { localIds[valueParameter.symbol] = it }
        // The implicit trailing `return@forEachProperty Unit` is just the end of the unrolled block.
        val (statements, trailingValue) = unrolled(lambda)
        val body = statements.map(::statement) + listOfNotNull(trailingValue?.let(::expr))
        return TExpr.ForEachProperty(
            receiver, nameLocal, nameParameter.name.asString(), valueLocal, valueParameter.name.asString(), body,
        )
    }

    private fun callee(function: IrSimpleFunction, at: IrElement): TCallee {
        val parent = function.parent
        if (parent is IrPackageFragment && parent.packageFqName == IrBuiltIns.KOTLIN_INTERNAL_IR_FQN) {
            return TCallee.Builtin(function.name.asString(), IrSignatures.of(function))
        }
        val property = function.correspondingPropertySymbol?.owner
        checkAvailableInTarget(property ?: function, at)
        val accessor = when {
            property == null -> TAccessor.FUNCTION
            property.getter == function -> TAccessor.GETTER
            else -> TAccessor.SETTER
        }
        val (packageFqName, className) = container(parent, at)
        return TCallee.Callable(
            packageFqName = packageFqName,
            className = className,
            name = (property?.name ?: function.name).asString(),
            accessor = accessor,
            signature = IrSignatures.of(function),
        )
    }

    /** (package, relative class name) of a callable container; file facades are transparent. */
    private fun container(parent: IrDeclarationParent, at: IrElement): Pair<String, String?> = when {
        parent is IrClass && parent.origin in FILE_FACADE_ORIGINS -> container(parent.parent, at)
        parent is IrClass -> {
            val classId = classId(parent, at)
            classId.packageFqName.asString() to classId.relativeClassName.asString()
        }
        parent is IrPackageFragment -> parent.packageFqName.asString() to null
        else -> fail(at, "call of a local declaration")
    }

    private fun constructorCall(e: IrConstructorCall): TExpr {
        val constructor = e.symbol.owner
        val constructedClass = constructor.constructedClass
        checkAvailableInTarget(constructedClass, e)
        return TExpr.ConstructorCall(
            classId = classId(constructedClass, e).asString(),
            signature = IrSignatures.of(constructor),
            typeArguments = e.typeArguments.map { type(it ?: fail(e, "constructor call without type arguments"), e) },
            constructorTypeArgumentsCount = constructor.typeParameters.size,
            arguments = e.arguments.map { it?.let(::expr) },
            type = type(e.type, e),
            origin = e.origin?.debugName,
        )
    }

    private fun loop(e: IrLoop): TExpr.Loop {
        val id = nextId++
        loopIds[e] = id
        val isDoWhile = e is IrDoWhileLoop
        // Encode in the same order as the materializer re-creates locals (do-while bodies come first).
        val body: TExpr?
        val condition: TExpr
        if (isDoWhile) {
            body = e.body?.let(::expr)
            condition = expr(e.condition)
        } else {
            condition = expr(e.condition)
            body = e.body?.let(::expr)
        }
        return TExpr.Loop(id, isDoWhile, e.label, condition, body, e.origin?.debugName)
    }

    private fun lambda(e: IrFunctionExpression): TExpr.Lambda {
        val lambda = e.function
        val id = nextId++
        returnTargetIds[lambda.symbol] = id
        val parameters = lambda.parameters.map { p ->
            val kind = when (p.kind) {
                IrParameterKind.ExtensionReceiver -> TExpr.LambdaParameterKind.EXTENSION_RECEIVER
                IrParameterKind.Regular -> TExpr.LambdaParameterKind.REGULAR
                else -> fail(p, "lambda parameter of kind ${p.kind}")
            }
            val parameterId = nextId++
            localIds[p.symbol] = parameterId
            TExpr.LambdaParameter(parameterId, kind, p.name.asString(), type(p.type, p))
        }
        val body = lambda.body as? IrBlockBody ?: fail(e, "lambda without a block body")
        return TExpr.Lambda(
            id = id,
            parameters = parameters,
            returnType = type(lambda.returnType, lambda),
            body = body.statements.map(::statement),
            type = type(e.type, e),
            origin = e.origin.debugName,
            isSuspend = lambda.isSuspend,
        )
    }

    private fun classId(irClass: IrClass, at: IrElement): ClassId =
        irClass.classId ?: fail(at, "local class ${irClass.name}")

    /** Index of the extension receiver in [IrFunction.parameters], or -1. */
    private val IrFunction.extensionReceiverIndex: Int get() = parameters.indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }

    /** Indexes of the regular parameters in [IrFunction.parameters]. */
    private val IrFunction.regularIndices: List<Int> get() = parameters.indices.filter { parameters[it].kind == IrParameterKind.Regular }

    companion object {
        /** Encodes a template lambda (`template { a: Int -> ... }`); see [capturedValues]. */
        fun forLambda(lambda: IrSimpleFunction): TemplateEncoder = TemplateEncoder(lambda, isLambda = true)

        private val UNIT = TType.Class("kotlin/Unit", emptyList(), isNullable = false)
        private val STRING = TType.Class("kotlin/String", emptyList(), isNullable = false)

        /** A by-name intrinsic of `boberc/Dynamic.kt`; a member extension takes its extension receiver after the name. */
        private class DynamicIntrinsic(val op: TDynamicOp, val takesMemberExtensionReceiver: Boolean = false)

        private val DYNAMIC_INTRINSICS = mapOf(
            "getMember" to DynamicIntrinsic(TDynamicOp.GET_MEMBER),
            "setMember" to DynamicIntrinsic(TDynamicOp.SET_MEMBER),
            "callMember" to DynamicIntrinsic(TDynamicOp.CALL_MEMBER),
            "callMemberExtension" to DynamicIntrinsic(TDynamicOp.CALL_MEMBER, takesMemberExtensionReceiver = true),
            "getMemberExtension" to DynamicIntrinsic(TDynamicOp.GET_MEMBER, takesMemberExtensionReceiver = true),
            "callFunction" to DynamicIntrinsic(TDynamicOp.CALL_FUNCTION),
            "callExtension" to DynamicIntrinsic(TDynamicOp.CALL_FUNCTION),
            "getProperty" to DynamicIntrinsic(TDynamicOp.GET_PROPERTY),
            "getExtension" to DynamicIntrinsic(TDynamicOp.GET_PROPERTY),
            "newInstance" to DynamicIntrinsic(TDynamicOp.NEW_INSTANCE),
            "objectInstance" to DynamicIntrinsic(TDynamicOp.GET_OBJECT),
            "isInstanceOf" to DynamicIntrinsic(TDynamicOp.IS_INSTANCE),
            "castTo" to DynamicIntrinsic(TDynamicOp.CAST),
        )

        private val FILE_FACADE_ORIGINS = setOf(
            IrDeclarationOrigin.FILE_CLASS,
            IrDeclarationOrigin.SYNTHETIC_FILE_CLASS,
            IrDeclarationOrigin.JVM_MULTIFILE_CLASS,
        )
    }
}
