package boberc.internal

import boberc.model.TVariance
import boberc.model.TVisibility
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.types.Variance

/** Model enums to their compiler counterparts, shared by the FIR and IR sides. */

internal fun TVisibility.toVisibility(): Visibility = when (this) {
  TVisibility.PUBLIC -> Visibilities.Public
  TVisibility.INTERNAL -> Visibilities.Internal
  TVisibility.PROTECTED -> Visibilities.Protected
  TVisibility.PRIVATE -> Visibilities.Private
}

internal fun TVariance.toVariance(): Variance = when (this) {
  TVariance.INVARIANT -> Variance.INVARIANT
  TVariance.IN -> Variance.IN_VARIANCE
  TVariance.OUT -> Variance.OUT_VARIANCE
}
