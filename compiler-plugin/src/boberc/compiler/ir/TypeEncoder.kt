package boberc.compiler.ir

import boberc.model.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.types.Variance

/**
 * Converts IR types of a template or shape into [TType]s. [typeParameters] are the type parameters
 * [TType.TypeParameter] indexes refer to; [owner] names the declaration in error messages.
 */
internal class TypeEncoder(
  private val typeParameters: List<IrTypeParameter>,
  private val owner: String,
  private val fail: (IrElement, String) -> Nothing,
) {
  fun type(type: IrType, at: IrElement): TType {
    val simple = type as? IrSimpleType ?: fail(at, "type ${type.render()}")
    val nullable = simple.isMarkedNullable()
    return when (val classifier = simple.classifier) {
      is IrClassSymbol -> {
        val classId = classifier.owner.classId ?: fail(at, "local class type ${type.render()}")
        val mirror = classifier.owner.mirrorTarget()
        if (classId == BobercNames.THIS_CLASS_ID || mirror == "") {
          TType.This(nullable)
        } else if (mirror != null) {
          TType.Named(mirror, simple.arguments.map { typeArgument(it, at) }, nullable)
        } else if (classId == BobercNames.DYNAMIC_CLASS_ID) {
          TType.Dynamic(nullable)
        } else if (classId in BobercNames.HANDLE_CLASS_IDS) {
          // A splice handle: bound to IR the plugin hands over; never materialized as a type.
          TType.Class(classId.asString(), emptyList(), nullable)
        } else {
          checkAvailableInTarget(classifier.owner, at)
          TType.Class(classId.asString(), simple.arguments.map { typeArgument(it, at) }, nullable)
        }
      }
      is IrTypeParameterSymbol -> {
        val index = typeParameters.indexOf(classifier.owner)
        if (index < 0) fail(at, "type parameter ${classifier.owner.name} that is not declared by $owner")
        TType.TypeParameter(index, nullable)
      }
      else -> fail(at, "type ${type.render()}")
    }
  }

  fun typeArgument(argument: IrTypeArgument, at: IrElement): TTypeArgument = when (argument) {
    is IrStarProjection -> TTypeArgument.Star
    is IrTypeProjection -> TTypeArgument.Projection(variance(argument.variance), type(argument.type, at))
  }

  fun variance(v: Variance): TVariance = when (v) {
    Variance.INVARIANT -> TVariance.INVARIANT
    Variance.IN_VARIANCE -> TVariance.IN
    Variance.OUT_VARIANCE -> TVariance.OUT
  }

  fun visibility(visibility: DescriptorVisibility): TVisibility = when (visibility) {
    DescriptorVisibilities.PRIVATE -> TVisibility.PRIVATE
    DescriptorVisibilities.INTERNAL -> TVisibility.INTERNAL
    DescriptorVisibilities.PROTECTED -> TVisibility.PROTECTED
    else -> TVisibility.PUBLIC
  }

  fun typeParameter(parameter: IrTypeParameter): TTypeParameter = TTypeParameter(
    name = parameter.name.asString(),
    variance = variance(parameter.variance),
    isReified = parameter.isReified,
    // The implicit `Any?` bound is the default for generated type parameters too.
    bounds = parameter.superTypes.filterNot { it.isNullableAny() }.map { type(it, parameter) },
  )

  /**
   * Declarations compiled in this module (i.e. in the plugin) do not exist in the target compilation,
   * so a template must not reference them. `boberc` declarations are compile-time only.
   */
  fun checkAvailableInTarget(declaration: IrDeclaration, at: IrElement) {
    if (declaration.fileOrNull != null) {
      val name = (declaration as? IrDeclarationWithName)?.name ?: "<anonymous>"
      fail(at, "reference to '$name', which is declared in the plugin module and won't exist in the target compilation")
    }
    val fqName = (declaration as? IrDeclarationWithName)?.fqNameWhenAvailable
    if (fqName != null && fqName.startsWith(BobercNames.BOBERC_PACKAGE)) {
      fail(at, "reference to '$fqName', boberc API is not available in the target compilation")
    }
  }

  fun IrClass.mirrorTarget(): String? {
    val annotation = getAnnotation(BobercNames.MIRROR_ANNOTATION) ?: return null
    return (annotation.arguments.getOrNull(0) as? IrConst)?.value as? String ?: ""
  }
}
