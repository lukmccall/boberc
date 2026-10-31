package boberc.fir

import boberc.internal.toVariance
import boberc.internal.toVisibility
import boberc.model.*
import boberc.plugin.BobercTemplateKey
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.plugin.SimpleFunctionBuildingContext
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createTopLevelFunction
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Generates a member function of [owner] whose signature is taken from [instance].
 *
 * The body is filled later by [boberc.plugin.BobercIrGenerationExtension] (it recognizes the
 * [BobercTemplateKey] the declaration is generated with), so make sure that extension is registered.
 *
 * With [isOverride], the function implements / overrides a member inherited by [owner] (e.g. `toString` or an
 * abstract interface function); its signature must match the overridden one.
 */
public fun FirExtension.createMemberFunctionFromTemplate(
    owner: FirClassSymbol<*>,
    instance: TemplateInstance,
    isOverride: Boolean = false,
): FirNamedFunction {
    val template = instance.template
    val types = TemplateTypes(session, owner, template)
    return createMemberFunction(
        owner, BobercTemplateKey(instance, isOverride), Name.identifier(instance.name),
        returnTypeProvider = { types.cone(template.returnType, it) },
    ) {
        configure(template, types, asMember = true)
        if (isOverride) {
            // Source overrides inherit `operator`/`infix` during status resolution, which generated declarations
            // skip; copy them from the overridden declarations (needed e.g. for `a < b` with `compareTo`).
            val inherited = inheritedModifiers(owner, Name.identifier(instance.name))
            status {
                this.isOverride = true
                if (inherited.isOperator) this.isOperator = true
                if (inherited.isInfix) this.isInfix = true
            }
        }
    }
}

private class InheritedModifiers(var isOperator: Boolean = false, var isInfix: Boolean = false)

private fun FirExtension.inheritedModifiers(owner: FirClassSymbol<*>, name: Name): InheritedModifiers {
    val result = InheritedModifiers()
    val superTypes = lookupSuperTypes(listOf(owner), lookupInterfaces = true, deep = true, useSiteSession = session, substituteTypes = false)
    for (superType in superTypes) {
        val superClass = superType.toRegularClassSymbol(session) ?: continue
        session.declaredMemberScope(superClass, memberRequiredPhase = null).processFunctionsByName(name) { function ->
            // Raw (declared) status: safe to read in any phase; the original declaration carries the modifiers.
            if (function.rawStatus.isOperator) result.isOperator = true
            if (function.rawStatus.isInfix) result.isInfix = true
        }
    }
    return result
}

/** Generates a top-level function in [packageFqName] whose signature is taken from [instance]. */
public fun FirExtension.createTopLevelFunctionFromTemplate(
    packageFqName: FqName,
    instance: TemplateInstance,
    containingFileName: String? = null,
): FirNamedFunction {
    val template = instance.template
    require(!template.requiresOwnerClass) {
        "Template '${template.name}' has a `This` receiver and can only be generated as a member function"
    }
    val types = TemplateTypes(session, owner = null, template)
    return createTopLevelFunction(
        BobercTemplateKey(instance), CallableId(packageFqName, Name.identifier(instance.name)),
        returnTypeProvider = { types.cone(template.returnType, it) },
        containingFileName = containingFileName,
    ) {
        configure(template, types, asMember = false)
    }
}

private fun SimpleFunctionBuildingContext.configure(template: FunctionTemplate, types: TemplateTypes, asMember: Boolean) {
    visibility = template.visibility.toVisibility()
    status {
        isOperator = template.isOperator
        isInfix = template.isInfix
    }
    for (typeParameter in template.typeParameters) {
        typeParameter(Name.identifier(typeParameter.name), typeParameter.variance.toVariance(), typeParameter.isReified) {
            for (bound in typeParameter.bounds) bound { types.cone(bound, it) }
        }
    }
    val receiver = template.receiverType
    // As a member, the template receiver becomes the dispatch receiver.
    if (receiver != null && !asMember) {
        extensionReceiverType { types.cone(receiver, it) }
    }
    for (parameter in template.signatureParameters(asMember)) {
        valueParameter(
            Name.identifier(parameter.name),
            { types.cone(parameter.type, it) },
            isCrossinline = parameter.isCrossinline,
            isNoinline = parameter.isNoinline,
            isVararg = parameter.varargElementType != null,
            hasDefaultValue = parameter.defaultValue != null,
        )
    }
}

/** Cone types of a template's signature; its type parameters are the generated function's own. */
private class TemplateTypes(private val session: FirSession, private val owner: FirClassSymbol<*>?, private val template: FunctionTemplate) {
    fun cone(type: TType, typeParameters: List<FirTypeParameterRef>): ConeKotlinType =
        ConeTypes(session, owner, "Template '${template.name}'") { parameter ->
            val symbol = typeParameters.getOrNull(parameter.index)?.symbol
                ?: error("Template '${template.name}' references unknown type parameter #${parameter.index}")
            ConeTypeParameterTypeImpl(ConeTypeParameterLookupTag(symbol), parameter.isNullable)
        }.cone(type)
}
