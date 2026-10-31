package boberc.ir

import boberc.model.FunctionTemplate
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.name.Name

/**
 * Builds IR from templates inside [scope]: a function, a constructor, or any declaration parent (a class, a field
 * whose initializer is built). Templates are usually zero-argument lambdas whose inputs are captured values and
 * splices (`exprRef`, `typeRef`, ...), so a plugin's own logic decides *what* to build and templates say *how*:
 *
 * ```
 * val quotes = IrQuotes(pluginContext, function)
 * val descriptor = quotes.expression(template { -> construct<SimpleMirror>(type.javaClass(), isNullable) })
 * ```
 *
 * A lambda template with parameters binds them to [scope]'s parameters like `replaceBody` does.
 * Failures throw [BobercTemplateException].
 */
public class IrQuotes(private val context: IrPluginContext, private val scope: IrDeclarationParent) {
    private val function: IrFunction? = scope as? IrFunction

    /** The template's body as an expression (its statements, then the value it ends with). */
    public fun expression(template: FunctionTemplate, origin: IrStatementOrigin? = null): IrExpression {
        val result = materializer(template).materializeExpression()
        if (origin != null) (result as? IrMemberAccessExpression<*>)?.origin = origin
        return result
    }

    /** The template's body as statements. */
    public fun statements(template: FunctionTemplate): List<IrStatement> = materializer(template).materializeStatements()

    /** `return <the template's value>` from the scope function. */
    public fun returning(template: FunctionTemplate): IrStatement {
        val function = function ?: throw BobercTemplateException("boberc: `returning` needs a function scope")
        return IrReturnImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, context.irBuiltIns.nothingType, function.symbol, expression(template))
    }

    /** A local declared in the scope (not yet added to any body); read/assign it from templates with `exprRef(it)`. */
    public fun variable(name: String, type: IrType, isVar: Boolean = false, initializer: FunctionTemplate? = null): IrVariable =
        buildVariable(scope, SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, IrDeclarationOrigin.DEFINED, Name.identifier(name), type, isVar)
            .also { variable -> variable.initializer = initializer?.let { expression(it) } }

    private fun materializer(template: FunctionTemplate) =
        IrTemplateMaterializer(context, function, template.instantiate(function?.name?.asString() ?: "expression"), scope)
}

/** Declarations a plugin adds in IR, to be filled by templates. */
public object IrDeclarations {
    /**
     * Adds a function to [owner]. [parameters] are its regular parameters; without [isStatic] it gets a dispatch
     * receiver. Fill its body with [IrTemplates.replaceBody].
     */
    public fun addFunction(
        owner: IrClass,
        name: String,
        returnType: IrType,
        visibility: DescriptorVisibility,
        origin: IrDeclarationOrigin,
        parameters: List<Pair<String, IrType>> = emptyList(),
        isStatic: Boolean = false,
        modality: Modality = Modality.FINAL,
        context: IrPluginContext,
    ): IrSimpleFunction {
        val function = context.irFactory.buildFun {
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
            this.name = Name.identifier(name)
            this.returnType = returnType
            this.visibility = visibility
            this.modality = modality
            this.origin = origin
        }
        function.parent = owner
        owner.declarations += function
        if (!isStatic) {
            function.parameters += owner.thisReceiver!!.copyTo(function, kind = IrParameterKind.DispatchReceiver)
        }
        for ((parameterName, type) in parameters) {
            function.addValueParameter(parameterName, type, IrDeclarationOrigin.DEFINED)
        }
        return function
    }

    /** Adds a field to [owner] (at [index], or last), initialized by the expression template [initializer]. */
    public fun addField(
        owner: IrClass,
        name: String,
        type: IrType,
        visibility: DescriptorVisibility,
        origin: IrDeclarationOrigin,
        isStatic: Boolean,
        isFinal: Boolean = true,
        initializer: FunctionTemplate? = null,
        index: Int? = null,
        context: IrPluginContext,
    ): IrField {
        val field = context.irFactory.buildField {
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
            this.name = Name.identifier(name)
            this.type = type
            this.visibility = visibility
            this.isFinal = isFinal
            this.isStatic = isStatic
            this.origin = origin
        }
        field.parent = owner
        if (initializer != null) setInitializer(field, initializer, context)
        if (index != null) owner.declarations.add(index, field) else owner.declarations += field
        return field
    }

    /** Replaces the initializer of [field] with the expression template [template]. */
    public fun setInitializer(field: IrField, template: FunctionTemplate, context: IrPluginContext, origin: IrStatementOrigin? = null) {
        val expression = IrQuotes(context, field).expression(template, origin)
        // An existing initializer keeps its body (and so its source position: a wrapped user initializer still
        // belongs to the line it was written on).
        val body = field.initializer
        if (body != null) {
            body.expression = expression
        } else {
            field.initializer = context.irFactory.createExpressionBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, expression)
        }
    }

    /** Adds the annotation the expression template [template] constructs (`construct<CalledFromNativeMirror>()`). */
    public fun addAnnotation(declaration: IrMutableAnnotationContainer, template: FunctionTemplate, context: IrPluginContext) {
        val scope = declaration as? IrDeclarationParent ?: (declaration as IrDeclaration).parent
        val call = IrQuotes(context, scope).expression(template) as? IrConstructorCall
            ?: throw BobercTemplateException("boberc: an annotation template must construct the annotation")
        val annotation = IrAnnotationImpl(
            call.startOffset, call.endOffset, call.type, call.symbol, call.typeArguments.size, call.constructorTypeArgumentsCount,
        ).apply {
            call.typeArguments.forEachIndexed { i, type -> typeArguments[i] = type }
            call.arguments.forEachIndexed { i, argument -> arguments[i] = argument }
        }
        declaration.annotations += annotation
    }

    /**
     * Turns the fake override [function] of an inherited member into a real member of its class (to be filled with
     * [IrTemplates.replaceBody]): it overrides what the fake override stood for.
     */
    public fun realizeFakeOverride(function: IrSimpleFunction, origin: IrDeclarationOrigin, modality: Modality = Modality.FINAL): IrSimpleFunction {
        require(function.isFakeOverride) { "${function.name} is not a fake override" }
        val overridden = function.overriddenSymbols.map { it.owner.resolveFakeOverride() ?: it.owner }.map { it.symbol }.distinct()
        function.isFakeOverride = false
        function.origin = origin
        function.modality = modality
        function.overriddenSymbols = overridden
        val owner = function.parent as IrClass
        function.dispatchReceiverParameter?.type = owner.defaultType
        return function
    }

    /** Initializes the backing field of [property] with the expression [template] and gives it the default getter. */
    public fun initializeProperty(property: IrProperty, template: FunctionTemplate, context: IrPluginContext) {
        val field = property.backingField ?: throw BobercTemplateException("boberc: property '${property.name}' has no backing field")
        setInitializer(field, template, context)
        defaultGetter(property, context)
    }

    /** Gives [property] the default getter body (`return field`) if it has none. */
    public fun defaultGetter(property: IrProperty, context: IrPluginContext) {
        val getter = property.getter ?: return
        if (getter.body != null) return
        val field = property.backingField ?: return
        val o = SYNTHETIC_OFFSET
        val receiver = getter.dispatchReceiverParameter?.let { IrGetValueImpl(o, o, it.type, it.symbol) }
        val read = IrGetFieldImpl(o, o, field.symbol, field.type, receiver)
        getter.body = context.irFactory.createBlockBody(o, o, listOf(IrReturnImpl(o, o, context.irBuiltIns.nothingType, getter.symbol, read)))
    }
}
