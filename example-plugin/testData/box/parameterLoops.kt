// End-to-end: `external` functions implemented by delegating to an overload, with parameters forwarded and
// inspected at compile time (forEachParameter / mapParameters / compile-time `if`).

// FILE: annotations.kt
package boberc.example

annotation class Delegated
annotation class Upper

// FILE: main.kt
package test

import boberc.example.Delegated
import boberc.example.Upper

class Service(val prefix: String) {
  val calls = mutableListOf<String>()
  fun log(name: String) { calls += name }

  @Delegated external fun greet(@Upper who: String, times: Int): String
  private fun greet(prefix: String, who: String, times: Int): String = "$prefix ${who.repeat(times)}"

  @Deprecated("for the test")
  @Delegated external fun count(): Int
  private fun count(prefix: String): Int = prefix.length

  @Delegated external fun sum(a: Int, b: Int, @Upper label: String): String
  private fun sum(prefix: String, a: Int, b: Int, label: String): String = "$prefix $label=${a + b}"
}

private fun check(actual: Any?, expected: Any?): String? =
  if (actual == expected) null else "expected <$expected> but was <$actual>"

fun box(): String {
  val service = Service("hi")
  val failures = listOfNotNull(
    check(service.greet("ab", 2), "hi ABAB"),
    check(service.count(), 2),
    check(service.sum(1, 2, "total"), "hi TOTAL=3"),
    check(service.calls, listOf("greet:times", "deprecated:count/0", "sum:a", "sum:b")),
    // The functions are no longer native.
    check(java.lang.reflect.Modifier.isNative(Service::class.java.getDeclaredMethod("count").modifiers), false),
  )
  return if (failures.isEmpty()) "OK" else failures.joinToString("\n")
}
