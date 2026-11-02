// End-to-end: the plugin hands exact functions to a template (FunctionRef), so nothing is resolved by name.

// FILE: annotations.kt
package boberc.example

annotation class Forward

// FILE: main.kt
package test

import boberc.example.Forward

val recorded = mutableListOf<String>()

fun record(what: String) { recorded += what }

class Calculator(val factor: Int) {
  @Forward fun scale(value: Int): Int = error("replaced")
  private fun scaleImpl(value: Int): Int = value * factor

  @Forward fun describe(label: String, value: Int): String = error("replaced")
  private fun describeImpl(label: String, value: Int): String = "$label=${value * factor}"
}

private fun check(actual: Any?, expected: Any?): String? =
  if (actual == expected) null else "expected <$expected> but was <$actual>"

fun box(): String {
  val calculator = Calculator(3)
  val failures = listOfNotNull(
    check(calculator.scale(2), 6),
    check(calculator.describe("x", 5), "x=15"),
    check(recorded, listOf("scale", "describe")),
  )
  return if (failures.isEmpty()) "OK" else failures.joinToString("\n")
}
