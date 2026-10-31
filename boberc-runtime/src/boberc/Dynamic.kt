@file:Suppress("UNUSED_PARAMETER", "unused")

package boberc

/*
 * Late-bound access to declarations that only exist in the *target* compilation (e.g. a property of the user's
 * class whose function body is being replaced). Everything here is a template-only intrinsic: the boberc
 * compiler plugin records the access symbolically and it is resolved, by name, when the template is applied.
 * Calling these functions at runtime throws.
 *
 * Names may be string constants or values captured by a template lambda (so the plugin can pick them per
 * class or per function).
 *
 * Resolution rules for all by-name calls:
 *  - the overload is chosen by arity and argument types; omitted trailing parameters use their default values
 *    (also when the default is declared in an overridden function), and [arg] passes a named argument;
 *  - type arguments of generic functions and classes are inferred from the arguments (and the receiver type,
 *    e.g. `Box<Int>`), then from the expected result type, falling back to upper bounds.
 */

/**
 * Declares a **mirror**: an interface describing members of a class that the plugin module cannot see.
 * Member accesses on a mirror are resolved by name (and arity/argument types) in the target compilation.
 *
 * - `@Mirror interface HasX { val x: Int }` (no name) stands for the class the template is applied to, like
 *   [This] with members: `template { self: HasX -> self.x * 2 }`.
 * - `@Mirror("com.example.Point") interface PointMirror { val x: Int }` stands for that specific class; it can
 *   be used in any type position (parameters, return types, casts: `newInstance("com.example.Point", 1, 2) as PointMirror`).
 *
 * Mirrors may declare properties (`val`/`var`) and functions without default arguments.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Mirror(val fqName: String = "")

/** A value whose class is only known in the target compilation (see [newInstance]). */
public sealed interface Dynamic

/** Reads the property [name] of the receiver (resolved against the receiver's class in the target compilation). */
public fun <T> Any?.getMember(name: String): T = intrinsic()

/** Writes the `var` property [name] of the receiver. */
public fun Any?.setMember(name: String, value: Any?): Unit = intrinsic()

/** Calls the member function [name] of the receiver; the overload is chosen by arity and argument types. */
public fun <T> Any?.callMember(name: String, vararg arguments: Any?): T = intrinsic()

/** Calls the top-level (or Java static) function with the fully qualified name [fqName], e.g. `"com.example.helper"`. */
public fun <T> callFunction(fqName: String, vararg arguments: Any?): T = intrinsic()

/** Reads the top-level property with the fully qualified name [fqName]. */
public fun <T> getProperty(fqName: String): T = intrinsic()

/** Creates an instance of the class [classFqName] (e.g. `"com.example.Point"` or `"com.example.Outer.Nested"`). */
public fun newInstance(classFqName: String, vararg arguments: Any?): Dynamic = intrinsic()

/** The instance of the `object` [classFqName]. */
public fun objectInstance(classFqName: String): Dynamic = intrinsic()

/** Calls the top-level extension function [fqName] on the receiver: `"hi".callExtension<String>("com.example.shout", 3)`. */
public fun <T> Any?.callExtension(fqName: String, vararg arguments: Any?): T = intrinsic()

/** Reads the top-level extension property [fqName] of the receiver. */
public fun <T> Any?.getExtension(fqName: String): T = intrinsic()

/**
 * Calls the *member extension* [name] declared in the receiver's class, with [extensionReceiver] as its extension
 * receiver: for `class X { fun String.tag(): String }`, `x.callMemberExtension<String>("tag", "a")` is
 * `with(x) { "a".tag() }`.
 */
public fun <T> Any?.callMemberExtension(name: String, extensionReceiver: Any?, vararg arguments: Any?): T = intrinsic()

/** Reads the member extension property [name] of the receiver's class for [extensionReceiver]. */
public fun <T> Any?.getMemberExtension(name: String, extensionReceiver: Any?): T = intrinsic()

/** A named argument for by-name calls: `box.callMember<String>("describe", arg("suffix", "?"))`. */
public fun arg(name: String, value: Any?): Any? = intrinsic()

/**
 * Compile-time loop over the properties of the receiver's class (declared and inherited; private ones only when
 * accessible from the function being generated). The block is **unrolled** once per property when the template
 * is applied; [value] has the property's actual type.
 *
 * ```
 * template { self: This -> buildString { self.forEachProperty { name, value -> append("$name=$value;") } } }
 * ```
 * `return@forEachProperty` is not supported (there is no lambda after unrolling); use `if`/`else` instead.
 */
public fun Any?.forEachProperty(action: (name: String, value: Any?) -> Unit): Unit = intrinsic()

/** `this is <classFqName>`. */
public fun Any?.isInstanceOf(classFqName: String): Boolean = intrinsic()

/** `this as <classFqName>`. */
public fun Any?.castTo(classFqName: String): Dynamic = intrinsic()

private fun intrinsic(): Nothing =
    throw IllegalStateException("boberc dynamic access functions can only be used inside templates")
