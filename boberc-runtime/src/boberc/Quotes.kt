@file:Suppress("UNUSED_PARAMETER", "unused")

package boberc

/*
 * Splices: IR the plugin already has (expressions, values, types, fields), handed to a template by capturing a
 * handle in the template lambda. They make templates *composable*: a plugin computes the parts with its own logic,
 * each part comes from a template, and templates insert other templates' results.
 *
 * Create the handles in the plugin with the factories in `boberc.ir` (`exprRef`, `exprListRef`, `typeRef`,
 * `fieldRef`) and apply templates with `boberc.ir.IrQuotes`. Everything here is a template-only intrinsic.
 */

/** An IR expression or value (parameter, variable) from the plugin. Each use inserts a copy of the expression. */
public interface ExprRef

/** The expression, typed as the template expects it: `descriptor.get<TypeDescriptorMirror>()`. */
public fun <T> ExprRef.get(): T = intrinsic()

/** Assigns a value referenced by [ExprRef] (a variable or a parameter). */
public fun ExprRef.set(value: Any?): Unit = intrinsic()

/** Several IR expressions from the plugin. */
public interface ExprListRef

/** The expressions as arguments: `arrayOf(*descriptors.spread<ObjectLikeMirror>())`, `builder.add(*args.spread())`. */
public fun <T> ExprListRef.spread(): Array<T> = intrinsic()

/** The expressions as statements, in order, right here. */
public fun ExprListRef.run(): Unit = intrinsic()

/** An IR type from the plugin. */
public interface TypeRef

/**
 * `T::class.java` for the type. A type mentioning type parameters is star-projected (`List<*>`), as a class
 * literal has to be; a concrete one such as `Array<String>` keeps its arguments, which decide the JVM class.
 */
public fun TypeRef.javaClass(): Class<*> = intrinsic()

/** The value cast to [type], unless it already has exactly that type (same class and nullability). */
public fun Any?.coerceTo(type: TypeRef): Dynamic = intrinsic()

/** Explicit type arguments for a by-name call: `reader.callMember<Dynamic>("read", typeArgs(fieldType), schema)`. */
public fun typeArgs(vararg types: TypeRef): Any? = intrinsic()

/** A field from the plugin (e.g. one it added in IR). */
public interface FieldRef

/** Reads a static field. */
public fun <T> FieldRef.get(): T = intrinsic()

/** Reads an instance field of the receiver. */
public fun <T> Any?.getField(field: FieldRef): T = intrinsic()

/** Writes an instance (or, with a `null` receiver, static) field. */
public fun Any?.setField(field: FieldRef, value: Any?): Unit = intrinsic()

private fun intrinsic(): Nothing =
    throw IllegalStateException("boberc splices can only be used inside templates")
