package boberc.ir

import boberc.ExprListRef
import boberc.ExprRef
import boberc.FieldRef
import boberc.FunctionRef
import boberc.TypeRef
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.render

/*
 * Handles for IR the plugin already has, to capture in template lambdas (see `boberc/Quotes.kt` and
 * `boberc/Target.kt` for what templates can do with them).
 */

/** A function: `ref.call(...)` or `receiver.callMember(ref, ...)` call exactly this symbol. */
public fun functionRef(function: IrSimpleFunction): FunctionRef = IrFunctionRef(function)

/** A constructor: `ref.call(...)` is `new`. */
public fun constructorRef(constructor: IrConstructor): FunctionRef = IrFunctionRef(constructor)

/**
 * An expression; each `ref.get<T>()` inserts a deep copy. Values read in it are replaced by [remap] (e.g. a
 * constructor parameter by the local holding it), so an expression can be moved into another function.
 */
public fun exprRef(expression: IrExpression, remap: Map<IrValueDeclaration, IrValueDeclaration> = emptyMap()): ExprRef =
  IrExprRef(expression, null, remap)

/** A value (parameter or variable): `ref.get<T>()` reads it, `ref.set(x)` assigns it. */
public fun exprRef(value: IrValueDeclaration): ExprRef = IrExprRef(null, value, emptyMap())

/** Several expressions: `*ref.spread()` passes them as arguments, `ref.run()` runs them as statements. */
public fun exprListRef(expressions: List<IrExpression>): ExprListRef = IrExprListRef(expressions)

/** A type: `ref.javaClass()`, `x.coerceTo(ref)`, `typeArgs(ref)`. */
public fun typeRef(type: IrType): TypeRef = IrTypeRef(type)

/** A field: `ref.get()` (static), `receiver.getField(ref)`, `receiver.setField(ref, value)`. */
public fun fieldRef(field: IrField): FieldRef = IrFieldRef(field)

internal class IrFunctionRef(val function: IrFunction) : FunctionRef {
  override fun toString(): String = "FunctionRef(${function.name})"
}

internal class IrExprRef(
  val expression: IrExpression?,
  val value: IrValueDeclaration?,
  val remap: Map<IrValueDeclaration, IrValueDeclaration>,
) : ExprRef {
  override fun toString(): String = "ExprRef(${value?.name ?: expression?.render()})"
}

internal class IrExprListRef(val expressions: List<IrExpression>) : ExprListRef {
  override fun toString(): String = "ExprListRef(${expressions.size})"
}

internal class IrTypeRef(val type: IrType) : TypeRef {
  override fun toString(): String = "TypeRef(${type.render()})"
}

internal class IrFieldRef(val field: IrField) : FieldRef {
  override fun toString(): String = "FieldRef(${field.name})"
}
