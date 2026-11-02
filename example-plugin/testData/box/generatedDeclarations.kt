// End-to-end: declarations the bobercPlugin {} DSL generates from shapes: a top-level class per selected class
// (`topLevelClass`, filtered by `where`), an `ir { }` block, and a sibling per selected function that the original
// calls exactly (`sibling` + `generatedSibling`).

// FILE: api.kt
package boberc.example

annotation class Registered

annotation class WithSibling

// FILE: main.kt
package test

import boberc.example.Registered
import boberc.example.WithSibling
import boberc.generated.PointInfo

@Registered
class Point {
  fun registeredAs(): String = "not registered"
}

// Generic: excluded by `where`, so no `BoxInfo` and `registeredAs` keeps its body.
@Registered
class Box<T> {
  fun registeredAs(): String = "not registered"
}

class Greeter {
  // Two originals with the same name: each gets its own overload, and calls exactly that one.
  @WithSibling
  fun greet(name: String): String = "not replaced"

  @WithSibling
  fun greet(name: String, times: Int): String = "not replaced"
}

private fun check(actual: Any?, expected: Any?): String? =
  if (actual == expected) null else "expected <$expected> but was <$actual>"

fun box(): String {
  val failures = listOfNotNull(
    check(PointInfo.typeName, "Point"),
    check(Point().registeredAs(), "registered as test.Point"),
    check(Box<Int>().registeredAs(), "not registered"),
    check(Greeter().greet("Ann"), "Hello Ann (from 1)"),
    check(Greeter().greet("Ann", 2), "Hello Ann 2 (from 2)"),
    // The siblings are real overloads, visible to user code.
    check(Greeter().greet("Hi", "Bob"), "Hi Bob (from 1)"),
  )
  return if (failures.isEmpty()) "OK" else failures.joinToString("\n")
}
