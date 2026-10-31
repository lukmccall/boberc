@file:Suppress("UNUSED_PARAMETER", "unused")

package boberc

import kotlin.reflect.KClass

/*
 * Template-only intrinsics for the context a template is applied in, and for declarations the plugin has in
 * hand. Calling them at runtime throws.
 */

/** The function the template is applied to (generated or redefined). All members are compile-time constants. */
public sealed interface TargetFunction {
    public val name: String

    /** Number of regular (value) parameters. */
    public val parameterCount: Int

    public fun hasAnnotation(annotationFqName: String): Boolean
    public fun hasAnnotation(annotation: KClass<out Annotation>): Boolean
}

/**
 * The function the template is applied to, e.g. to delegate to a same-name overload without capturing its
 * name: `self.callMember<Dynamic>(targetFunction.name, ...)`.
 */
public val targetFunction: TargetFunction get() = intrinsic()

/**
 * A function of the target compilation chosen by the plugin itself: create it in the plugin with
 * `boberc.ir.functionRef(irFunction)` and capture it in a template lambda. Calls go to exactly that
 * symbol, with no resolution by name, which also reaches functions that exist only in IR.
 *
 * ```
 * val define = functionRef(defineFunction)
 * template { -> define.call<String?>(builder) }
 * ```
 */
public interface FunctionRef

/** Calls a static / top-level [FunctionRef]. Arguments are matched like any by-name call (defaults, [arg], varargs). */
public fun <R> FunctionRef.call(vararg arguments: Any?): R = intrinsic()

/** Calls the member (or extension) function [function] on the receiver. */
public fun <R> Any?.callMember(function: FunctionRef, vararg arguments: Any?): R = intrinsic()

/**
 * The instance of the `object` a named [Mirror] stands for, typed as the mirror:
 * `mirror<RegistryMirror>().register(...)` instead of `objectInstance("a.Registry") as RegistryMirror`.
 */
public fun <M : Any> mirror(): M = intrinsic()

/** A new instance of the class a named [Mirror] stands for: `construct<PointMirror>(1, 2)`. */
public fun <M : Any> construct(vararg arguments: Any?): M = intrinsic()

private fun intrinsic(): Nothing =
    throw IllegalStateException("boberc target functions can only be used inside templates")
