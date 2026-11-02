// RUN_PIPELINE_TILL: FRONTEND
// DIAGNOSTICS: -UNUSED_VARIABLE
// Generated declarations are visible to (and type-checked by) the frontend.

// FILE: Greeter.kt
package boberc.example

annotation class Greeter

// FILE: main.kt
package test

import boberc.example.Greeter

@Greeter
class Person

class NotAGreeter

fun test(p: Person, n: NotAGreeter) {
  val s: String = p.greet("Bob")
  val list: List<Int> = p.replicate(1, 2)
  val self: Person = p.tap { }
  p.greet(<!ARGUMENT_TYPE_MISMATCH!>42<!>)
  val wrong: List<String> <!INITIALIZER_TYPE_MISMATCH!>=<!> p.replicate(1, 2)
  n.<!UNRESOLVED_REFERENCE!>greet<!>("Bob")
}
