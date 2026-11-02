package boberc.example

import boberc.Mirror

/*
 * Mirrors: typed views of declarations that only exist in the code the plugin compiles.
 * Member accesses are resolved by name when the template is applied.
 */

/** Any class with `val x: Int` and `fun twice(n: Int): Int` (bound to the class the template is applied to). */
@Mirror
interface HasX {
  val x: Int
  fun twice(n: Int): Int
}

/** The user's class `test.Point`: usable in signatures and casts, too. */
@Mirror("test.Point")
interface PointMirror {
  val x: Int
  val y: Int
}

/** A generic user class: `BoxMirror<String>` is `test.Box<String>`. Generic members and default arguments work. */
@Mirror("test.Box")
interface BoxMirror<T> {
  val value: T
  fun describe(prefix: String = "Box", suffix: String = "!"): String
  fun <R> map(transform: (T) -> R): BoxMirror<R>
}

/** Member extensions declared in the user's class. */
@Mirror
interface Tagger {
  fun String.tag(): String
  val Int.doubled: Int
}

/** The user's `object test.Registry`: `mirror<RegistryMirror>()` is that object. */
@Mirror("test.Registry")
interface RegistryMirror {
  val name: String
}
