@file:Suppress("UNUSED_PARAMETER", "unused")

package boberc

import kotlin.reflect.KClass

/*
 * Compile-time access to the parameters of the function a template is applied to: useful when the template
 * cannot know the arity, e.g. "delegate every parameter to another function" or "log all arguments".
 * Everything here is unrolled when the template is applied, once per (regular) parameter.
 */

/**
 * A parameter of the function the template is applied to. Only usable through its members, inside
 * [forEachParameter] / [mapParameters]. Except for [value], all members are compile-time constants, so
 * `if (p.hasAnnotation("..."))` keeps only the branch that applies (the other one is never resolved).
 */
public sealed interface TemplateParameter {
  public val name: String
  public val index: Int

  /** The argument passed for this parameter, with the parameter's actual type. */
  public val value: Any?

  public fun hasAnnotation(annotationFqName: String): Boolean

  /** [hasAnnotation] with a class literal, when the annotation class is on the plugin's classpath. */
  public fun hasAnnotation(annotation: KClass<out Annotation>): Boolean

  /** Whether the parameter's type is the class [classFqName] or a subtype of it. */
  public fun isSubtypeOf(classFqName: String): Boolean

  public fun isSubtypeOf(type: KClass<*>): Boolean
}

/** Runs [action] for each regular parameter of the function the template is applied to (unrolled). */
public fun forEachParameter(action: (parameter: TemplateParameter) -> Unit): Unit = intrinsic()

/**
 * Inside the arguments of a by-name call (`callMember`, `callFunction`, `newInstance`, ...): expands to one
 * argument per regular parameter of the function the template is applied to, computed by [transform].
 *
 * ```
 * self.callMember<Dynamic>(name, self.getMember<Long>("pointer"), mapParameters { p -> p.value })
 * ```
 */
public fun mapParameters(transform: (parameter: TemplateParameter) -> Any?): Any? = intrinsic()

private fun intrinsic(): Nothing =
  throw IllegalStateException("boberc parameter functions can only be used inside templates")
