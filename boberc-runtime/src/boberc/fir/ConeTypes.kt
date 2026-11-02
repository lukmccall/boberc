package boberc.fir

import boberc.model.ClassNames
import boberc.model.TType
import boberc.model.TTypeArgument
import boberc.model.TVariance
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.ClassId

/**
 * Converts model types into cone types for a declaration generated from a template or shape ([declaration]
 * names it in errors). [owner] is the class `This` stands for; [typeParameter] resolves [TType.TypeParameter].
 */
internal class ConeTypes(
  private val session: FirSession,
  private val owner: FirClassSymbol<*>?,
  private val declaration: String,
  private val typeParameter: (TType.TypeParameter) -> ConeKotlinType,
) {
  fun cone(type: TType): ConeKotlinType = when (type) {
    is TType.Class -> ClassId.fromString(type.classId).createConeType(session, projections(type.arguments), type.isNullable)
    is TType.TypeParameter -> typeParameter(type)
    is TType.This -> {
      val owner = owner ?: error("$declaration uses `This` (or an unnamed mirror) where there is no class to generate into")
      owner.constructType(owner.typeParameterSymbols.map { it.toConeType() }.toTypedArray(), type.isNullable)
    }
    is TType.Named -> {
      val classId = ClassNames.firstResolved(type.fqName) { candidate ->
        ClassId.fromString(candidate).takeIf { session.symbolProvider.getClassLikeSymbolByClassId(it) != null }
      } ?: error("$declaration references class ${type.fqName}, which is not available in this compilation")
      classId.createConeType(session, projections(type.arguments), type.isNullable)
    }
    // A value of a target-only class: `Any?` in signatures.
    is TType.Dynamic -> session.builtinTypes.nullableAnyType.coneType
  }

  private fun projections(arguments: List<TTypeArgument>): Array<ConeTypeProjection> = arguments.map { argument ->
    when (argument) {
      TTypeArgument.Star -> ConeStarProjection
      is TTypeArgument.Projection -> {
        val type = cone(argument.type)
        when (argument.variance) {
          TVariance.INVARIANT -> type
          TVariance.IN -> ConeKotlinTypeProjectionIn(type)
          TVariance.OUT -> ConeKotlinTypeProjectionOut(type)
        }
      }
    }
  }.toTypedArray()
}
