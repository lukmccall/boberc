package boberc

import boberc.model.FunctionTemplate

/**
 * Marks a top-level function as a **template**: ordinary Kotlin code describing a declaration that a compiler
 * plugin will generate into *another* compilation.
 *
 * When the module containing the template is compiled with the boberc compiler plugin, the signature and the
 * fully-resolved body of the function are captured into a [FunctionTemplate] model. References to other
 * declarations are recorded symbolically (by class/callable id and signature) and re-resolved in the target
 * compilation, so templates may only reference declarations that are visible there (e.g. the standard library
 * or a runtime library of the generated plugin), never declarations of the plugin module itself.
 *
 * The template function is still compiled as a normal function, so it can be unit-tested as plain Kotlin
 * code (unless it uses [This]).
 *
 * ```
 * @Template
 * fun This.greet(@Splice className: String, name: String = "stranger"): String =
 *     "Hello, $name! I am $className."
 *
 * val greet: FunctionTemplate = template(This::greet)
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Template

/**
 * Marks a template parameter as a compile-time **splice**: the parameter does not appear in the generated
 * function; every usage of it in the body is replaced by a constant provided by the plugin when the template is
 * instantiated (see [boberc.model.FunctionTemplate.instantiate]).
 *
 * Splice parameters must have a primitive type, `String` (or a nullable version of those), or be a handle of IR
 * the plugin hands over (`ExprRef`, `TypeRef`, `FunctionRef`, ... see `boberc/Quotes.kt`).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Splice

/**
 * Placeholder for "the class the declaration is generated into".
 *
 * A template declared as an extension on [This] (`fun This.foo()`) can only be generated as a member function;
 * its receiver becomes the dispatch receiver of the generated member. [This] may also be used in any type
 * position (`fun This.copy(): This`).
 */
public sealed interface This

/**
 * Compiler intrinsic: returns the [FunctionTemplate] model of [function], which must be either
 *
 * - a callable reference to a `@Template` function of the same module: `template(::fizzBuzz)`,
 *   `template(This::greet)`, or
 * - a **lambda literal** with explicitly typed parameters: `template { self: This, other: This -> ... }`.
 *
 * Binding: a leading `self: This` parameter binds the **dispatch receiver** (for any arity); the other lambda
 * parameters bind to the remaining parameters of the function the template is applied to, aligned from the
 * **end**. For `fun compareTo(other: T): Int`, `{ other: This -> ... }` binds `other` and
 * `{ self: This, other: This -> ... }` additionally binds the receiver; `{ self: This -> ... }` works for a
 * function with any number of parameters (reach them with [forEachParameter] / [mapParameters]). When a lambda
 * template is used to *generate* a member, a leading parameter of type [This] is the dispatch receiver.
 *
 * Values captured by the lambda (locals of the plugin code, of primitive or `String` type) become splices
 * bound to their value at the time `template { }` is evaluated, which is handy for per-class or per-function
 * values: `val n = className; template { self: This -> "I am $n" }`.
 *
 * Calls are replaced by the boberc compiler plugin; the lambda itself is never executed.
 */
@Suppress("UNUSED_PARAMETER")
public fun template(function: Function<*>): FunctionTemplate =
    throw IllegalStateException(
        "boberc.template() is a compiler intrinsic. Apply the boberc compiler plugin to the module declaring templates."
    )

/**
 * Compiler intrinsic: returns the [FunctionTemplate] model of the `@Template` function named [name].
 *
 * Use this overload for **generic** templates: Kotlin cannot infer type arguments of a callable reference like
 * `::myGenericTemplate`, and explicit type arguments are not allowed there. [name] must be a constant; it is
 * resolved at compile time among templates of the same module, preferring the caller's package, and may be a
 * fully qualified name (`com.example.myTemplate`).
 */
@Suppress("UNUSED_PARAMETER")
public fun template(name: String): FunctionTemplate =
    throw IllegalStateException(
        "boberc.template() is a compiler intrinsic. Apply the boberc compiler plugin to the module declaring templates."
    )

/**
 * In a template, makes the local `val` it initializes a compiler **temporary** (`val result = temporary(call())`):
 * generated as an `IR_TEMPORARY_VARIABLE` named like the IR builder's (`tmp_0`, ...). That is invisible to
 * debuggers and absent from the local variable table, like the temporaries hand-written IR creates. Outside
 * templates it simply returns [value].
 */
public fun <T> temporary(value: T): T = value

@RequiresOptIn("Internal boberc API used by compiler-generated code; do not use directly.")
public annotation class BobercInternalApi
