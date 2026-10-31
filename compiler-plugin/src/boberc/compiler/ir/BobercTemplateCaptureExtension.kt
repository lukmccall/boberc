package boberc.compiler.ir

import boberc.compiler.BobercErrors
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.getArrayElementType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/**
 * 1. Captures every `@Template` function of the module into a template model (reporting unsupported code).
 * 2. Replaces `boberc.template(::f)` with `boberc.internal.decodeFunctionTemplate("<encoded model>")`.
 * 3. Captures every `@Shape` class and replaces `boberc.shape(X::class)` with `boberc.internal.decodeClassShape(...)`.
 */
class BobercTemplateCaptureExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val captured = Captured()
        for (file in moduleFragment.files) {
            file.acceptChildrenVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

                override fun visitClass(declaration: IrClass) {
                    if (declaration.hasAnnotation(BobercNames.SHAPE_ANNOTATION)) {
                        captured.shapes[declaration.symbol] = pluginContext.encodeOrReport(file) { ShapeEncoder(declaration).encode().encode() }
                    }
                    super.visitClass(declaration)
                }

                override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                    if (declaration.hasAnnotation(BobercNames.TEMPLATE_ANNOTATION)) {
                        captured.templates[declaration] = pluginContext.encodeOrReport(file) { TemplateEncoder(declaration).encode().encode() }
                    }
                    super.visitSimpleFunction(declaration)
                }
            })
        }

        val decoders = Decoders(pluginContext)
        for (file in moduleFragment.files) {
            file.transformChildrenVoid(IntrinsicTransformer(file, pluginContext, captured, decoders))
        }
    }

    /** The models captured in this module; `null` when encoding failed (the error is already reported). */
    private class Captured {
        val templates = LinkedHashMap<IrSimpleFunction, String?>()
        val shapes = HashMap<IrClassSymbol, String?>()
    }

    /** The `boberc.internal` functions the intrinsics are lowered to, resolved on first use. */
    private class Decoders(context: IrPluginContext) {
        private val finder = context.finderForBuiltins()

        val function by lazy { find(BobercNames.DECODE_FUNCTION_NAME) }
        val lambda by lazy { find(BobercNames.DECODE_LAMBDA_NAME) }
        val shape by lazy { find(BobercNames.DECODE_SHAPE_NAME) }

        private fun find(name: String): IrSimpleFunctionSymbol =
            finder.findFunctions(CallableId(BobercNames.DECODE_FUNCTION_PACKAGE, Name.identifier(name))).singleOrNull()
                ?: error("boberc-runtime is not on the classpath: cannot find ${BobercNames.DECODE_FUNCTION_PACKAGE}.$name")
    }

    private class IntrinsicTransformer(
        private val file: IrFile,
        private val context: IrPluginContext,
        private val captured: Captured,
        private val decoders: Decoders,
    ) : IrElementTransformerVoid() {
        override fun visitCall(expression: IrCall): IrExpression {
            expression.transformChildrenVoid(this)
            val callee = expression.symbol.owner
            if (callee.name.asString() == "shape" && callee.kotlinFqName == BobercNames.SHAPE_INTRINSIC) return shapeOf(expression)
            if (callee.name.asString() != "template" || callee.kotlinFqName != BobercNames.TEMPLATE_INTRINSIC) return expression

            val argument = expression.arguments.getOrNull(0)
            if (argument != null && argument.type.isString()) {
                return templateByName(expression, argument)
            }
            if (argument is IrFunctionExpression) {
                return templateFromLambda(expression, argument)
            }
            val target = when (argument) {
                is IrFunctionReference -> argument.symbol.owner
                is IrRichFunctionReference -> argument.reflectionTargetSymbol?.owner
                else -> null
            } as? IrSimpleFunction

            if (target == null || !target.hasAnnotation(BobercNames.TEMPLATE_ANNOTATION)) {
                context.diagnosticReporter.at(expression, file).report(BobercErrors.NOT_A_TEMPLATE_REFERENCE)
                return expression
            }
            return replaceWithModel(expression, target)
        }

        /** `shape(X::class)`: the encoded `@Shape` class of this module. */
        private fun shapeOf(expression: IrCall): IrExpression {
            val shapeClass = ((expression.arguments.getOrNull(0) as? IrClassReference)?.symbol as? IrClassSymbol)
            if (shapeClass == null || !shapeClass.owner.hasAnnotation(BobercNames.SHAPE_ANNOTATION)) {
                context.diagnosticReporter.at(expression, file).report(BobercErrors.NOT_A_SHAPE_REFERENCE)
                return expression
            }
            if (shapeClass !in captured.shapes) {
                context.diagnosticReporter.at(expression, file)
                    .report(BobercErrors.TEMPLATE_FROM_OTHER_MODULE, shapeClass.owner.kotlinFqName.asString())
                return expression
            }
            val data = captured.shapes[shapeClass] ?: return expression // already reported
            return buildDecodeCall(expression, decoders.shape, data)
        }

        /** `template { a: Int -> ... }`: captures the lambda itself; captured values are passed at runtime. */
        private fun templateFromLambda(expression: IrCall, lambda: IrFunctionExpression): IrExpression {
            val encoder = TemplateEncoder.forLambda(lambda.function)
            val data = context.encodeOrReport(file) { encoder.encode().encode() } ?: return expression
            val decode = decoders.lambda
            val start = expression.startOffset
            val end = expression.endOffset
            val builtIns = context.irBuiltIns
            val capturedArray = IrCallImpl(
                start, end, builtIns.arrayClass.typeWith(builtIns.anyNType), builtIns.arrayOf, typeArgumentsCount = 1,
            ).apply {
                typeArguments[0] = builtIns.anyNType
                arguments[0] = IrVarargImpl(
                    start, end, builtIns.arrayClass.typeWith(builtIns.anyNType), builtIns.anyNType,
                    encoder.capturedValues.map { IrGetValueImpl(start, end, it) },
                )
            }
            return IrCallImpl(start, end, expression.type, decode, typeArgumentsCount = 0).apply {
                arguments[0] = capturedArray
                arguments[1] = stringParts(decode.owner.parameters[1].type, data, start, end)
            }
        }

        /** `template("name")`: resolves among the module's templates, preferring the caller's package. */
        private fun templateByName(expression: IrCall, argument: IrExpression): IrExpression {
            val name = (argument as? IrConst)?.value as? String
            if (name == null) {
                context.diagnosticReporter.at(expression, file).report(BobercErrors.TEMPLATE_NOT_FOUND, "<non-constant>")
                return expression
            }
            val templates = captured.templates.keys
            val byFqName = templates.filter { it.kotlinFqName.asString() == name }
            val candidates = byFqName.ifEmpty {
                val sameName = templates.filter { it.name.asString() == name }
                sameName.filter { it.parent.kotlinFqName == file.packageFqName }.ifEmpty { sameName }
            }
            return when (candidates.size) {
                1 -> replaceWithModel(expression, candidates.single())
                0 -> expression.also { context.diagnosticReporter.at(expression, file).report(BobercErrors.TEMPLATE_NOT_FOUND, name) }
                else -> expression.also {
                    context.diagnosticReporter.at(expression, file).report(BobercErrors.AMBIGUOUS_TEMPLATE_NAME, name)
                }
            }
        }

        private fun replaceWithModel(expression: IrCall, target: IrSimpleFunction): IrExpression {
            if (target !in captured.templates) {
                context.diagnosticReporter.at(expression, file)
                    .report(BobercErrors.TEMPLATE_FROM_OTHER_MODULE, target.kotlinFqName.asString())
                return expression
            }
            val data = captured.templates[target] ?: return expression // already reported
            return buildDecodeCall(expression, decoders.function, data)
        }

        private fun buildDecodeCall(original: IrCall, decode: IrSimpleFunctionSymbol, data: String): IrExpression {
            val start = original.startOffset
            val end = original.endOffset
            return IrCallImpl(start, end, original.type, decode, typeArgumentsCount = 0).apply {
                arguments[0] = stringParts(decode.owner.parameters.single().type, data, start, end)
            }
        }

        private fun stringParts(varargType: IrType, data: String, start: Int, end: Int): IrVararg {
            val stringType = context.irBuiltIns.stringType
            val parts = data.chunked(CHUNK_SIZE).map { IrConstImpl.string(start, end, stringType, it) }
            return IrVarargImpl(start, end, varargType, varargType.getArrayElementType(context.irBuiltIns), parts)
        }
    }

    private companion object {
        /** Runs [encode], reporting what it cannot capture as `UNSUPPORTED_IN_TEMPLATE`; `null` when it failed. */
        fun IrPluginContext.encodeOrReport(file: IrFile, encode: () -> String): String? = try {
            encode()
        } catch (e: TemplateEncodingException) {
            diagnosticReporter.at(e.element, file).report(BobercErrors.UNSUPPORTED_IN_TEMPLATE, e.message ?: "unknown construct")
            null
        }

        /** Keeps every string constant well below the 65535-byte JVM constant pool limit. */
        const val CHUNK_SIZE = 16_000
    }
}
