// End-to-end: FIR declarations generated from @Shape classes (supertype, nested class, companion, members),
// bodies filled from templates.

// FILE: api.kt
package boberc.example

annotation class Described

interface Describable

interface Describer<T> {
  val typeName: String
  fun describe(value: T): String
}

// FILE: main.kt
package test

import boberc.example.Describable
import boberc.example.Described
import boberc.example.Describer

@Described
class Point(val x: Int, val y: Int)

@Described
class Named(val name: String) {
  companion object {
    val greeting = "hi"
  }
}

// Compiles only because the supertype was added in FIR.
fun asDescribable(value: Point): Describable = value

private fun check(actual: Any?, expected: Any?): String? =
  if (actual == expected) null else "expected <$expected> but was <$actual>"

fun box(): String {
  val pointDescriber: Describer<Point> = Point.describer
  val failures = listOfNotNull(
    check(asDescribable(Point(1, 2)) is Describable, true),
    check(pointDescriber.typeName, "Point"),
    check(pointDescriber.describe(Point(1, 2)), "Point(x=1, y=2)"),
    check(Named.describer.describe(Named("Ann")), "Named(name=Ann)"),
    check(Named.greeting, "hi"),
    check(Point.describer === Point.describer, true),
  )
  return if (failures.isEmpty()) "OK" else failures.joinToString("\n")
}
