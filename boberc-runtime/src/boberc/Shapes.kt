package boberc

import boberc.model.ClassShape
import kotlin.reflect.KClass

/**
 * Marks a class or interface as a **shape**: the plain-Kotlin description of declarations a plugin generates
 * in FIR (a class, its supertypes, constructors and member signatures). The boberc compiler plugin captures it
 * into a [ClassShape]; get it with [shape] and generate from it with the `boberc.fir` functions
 * (`createNestedClassFromShape`, `createPropertiesFromShape`, ...).
 *
 * ```
 * @Shape
 * private abstract class CodecShape<R> : CodecMirror<R> {   // R is bound per generated class
 *   abstract override val recordClass: Class<R>
 *   abstract override fun decode(reader: ReaderMirror): R
 * }
 * ```
 *
 * - Type parameters of the shape stand for types chosen by the plugin when the shape is bound; types of the
 *   target compilation are written with [Mirror]s, and [This] is the generated class.
 * - Member bodies are ignored: FIR declares the members, the IR phase (templates) fills their bodies. In a
 *   class shape, `abstract` members are therefore generated as final members; in an interface shape they stay
 *   abstract. The shape's class itself may be `abstract` (it is generated final) or `open` (generated open).
 * - The visibility of the shape and of its members is the visibility of the generated declarations.
 * - Members with their own type parameters are not supported.
 * - A `vararg` parameter of type [ParameterSplice] stands for parameters the plugin computes per generated
 *   declaration (e.g. derived from a user's function); see `boberc.fir.ShapeParameter`.
 * - `external` functions are generated `external`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Shape

/**
 * In a `@Shape` function or constructor, `vararg name: ParameterSplice` marks where the parameters the plugin
 * passes when generating it are inserted:
 *
 * ```
 * @Shape private abstract class NativeOverloadShape<R> {
 *   private external fun overload(pointer: Long, vararg parameters: ParameterSplice): R
 * }
 * createFunctionFromShape(owner, shape.bind(returnType), "overload", Key, name = original.name,
 *   parameters = original.valueParameterSymbols.map { ShapeParameter(it.name, typeFor(it)) })
 * ```
 */
public sealed interface ParameterSplice

/**
 * Compiler intrinsic: returns the [ClassShape] of the `@Shape` class [shapeClass] of the same module
 * (`shape(CodecShape::class)`). Calls are replaced by the boberc compiler plugin.
 */
@Suppress("UNUSED_PARAMETER")
public fun shape(shapeClass: KClass<*>): ClassShape =
    throw IllegalStateException(
        "boberc.shape() is a compiler intrinsic. Apply the boberc compiler plugin to the module declaring shapes."
    )
