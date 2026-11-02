package boberc.compiler.ir

import boberc.model.*
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.primaryConstructor

/** Captures a `@Shape` class into a [ClassShape]: declarations and signatures only, bodies are ignored. */
internal class ShapeEncoder(private val shape: IrClass) {
  private fun fail(element: IrElement, message: String): Nothing = throw TemplateEncodingException(element, message)

  private val types = TypeEncoder(shape.typeParameters, "shape '${shape.name}'", ::fail)

  fun encode(): ClassShape {
    val kind = when (shape.kind) {
      ClassKind.CLASS -> TClassKind.CLASS
      ClassKind.INTERFACE -> TClassKind.INTERFACE
      ClassKind.OBJECT -> TClassKind.OBJECT
      else -> fail(shape, "shapes must be classes, interfaces or objects")
    }
    if (shape.parent !is IrFile) fail(shape, "shapes must be top-level declarations")
    if (shape.isInner || shape.isData || shape.isValue || shape.isFun) fail(shape, "inner, data, value or fun interface shapes")

    val primary = shape.primaryConstructor
    val constructors = shape.declarations.filterIsInstance<IrConstructor>().map { constructor ->
      ConstructorShape(types.visibility(constructor.visibility), constructor == primary, constructor.parameters
        .filter { it.kind == IrParameterKind.Regular }
        .map(::parameter))
    }
    val members = shape.declarations.mapNotNull { declaration ->
      when (declaration) {
        is IrProperty -> if (declaration.isFakeOverride) null else property(declaration)
        is IrSimpleFunction -> if (declaration.isFakeOverride || declaration.correspondingPropertySymbol != null) null else function(declaration)
        is IrConstructor -> null
        is IrClass -> fail(declaration, "nested classes in shapes")
        else -> fail(declaration, "this declaration in a shape")
      }
    }
    return ClassShape(
      name = shape.name.asString(),
      kind = kind,
      visibility = types.visibility(shape.visibility),
      isOpen = kind == TClassKind.CLASS && shape.modality == Modality.OPEN,
      typeParameters = shape.typeParameters.map(types::typeParameter),
      supertypes = shape.superTypes.filterNot { it.isAny() }.map { types.type(it, shape) },
      constructors = constructors,
      members = members,
    )
  }

  private fun property(property: IrProperty): MemberShape.Property {
    val getter = property.getter ?: fail(property, "shape property '${property.name}' without a getter")
    if (getter.parameters.any { it.kind == IrParameterKind.ExtensionReceiver || it.kind == IrParameterKind.Context }) {
      fail(property, "extension or context properties in shapes")
    }
    if (property.typeParameters().isNotEmpty()) fail(property, "generic properties in shapes")
    return MemberShape.Property(
      name = property.name.asString(),
      visibility = types.visibility(property.visibility),
      modality = modality(property.modality),
      isOverride = getter.overriddenSymbols.isNotEmpty(),
      type = types.type(getter.returnType, property),
      isVar = property.isVar,
    )
  }

  private fun IrProperty.typeParameters(): List<IrTypeParameter> = getter?.typeParameters.orEmpty()

  private fun function(function: IrSimpleFunction): MemberShape.Function {
    if (function.typeParameters.isNotEmpty()) fail(function, "shape functions with their own type parameters")
    if (function.isSuspend) fail(function, "suspend functions in shapes")
    if (function.parameters.any { it.kind == IrParameterKind.Context }) fail(function, "context parameters in shapes")
    return MemberShape.Function(
      name = function.name.asString(),
      visibility = types.visibility(function.visibility),
      modality = modality(function.modality),
      isOverride = function.overriddenSymbols.isNotEmpty(),
      isOperator = function.isOperator,
      isInfix = function.isInfix,
      isExternal = function.isExternal,
      receiverType = function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }?.let { types.type(it.type, it) },
      parameters = function.parameters.filter { it.kind == IrParameterKind.Regular }.map(::parameter),
      returnType = types.type(function.returnType, function),
    )
  }

  private fun parameter(p: IrValueParameter): TParameter {
    if (p.defaultValue != null) fail(p, "default values in shapes (declare the parameter without one)")
    if ((p.varargElementType?.classOrNull?.owner?.classId) == BobercNames.PARAMETER_SPLICE_CLASS_ID) {
      // `vararg name: ParameterSplice`: where the plugin's parameters go.
      val marker = TType.Class(BobercNames.PARAMETER_SPLICE_CLASS_ID.asString(), emptyList(), false)
      return TParameter(p.name.asString(), marker, isSplice = true, varargElementType = null, false, false, null)
    }
    return TParameter(
      name = p.name.asString(),
      type = types.type(p.type, p),
      isSplice = false,
      varargElementType = p.varargElementType?.let { types.type(it, p) },
      isCrossinline = p.isCrossinline,
      isNoinline = p.isNoinline,
      defaultValue = null,
    )
  }

  private fun modality(modality: Modality): TModality = when (modality) {
    Modality.ABSTRACT -> TModality.ABSTRACT
    Modality.OPEN -> TModality.OPEN
    else -> TModality.FINAL
  }
}
