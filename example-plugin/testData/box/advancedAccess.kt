// End-to-end: generic members and classes, extension members, default/named arguments and property loops.

// FILE: main.kt
package test

class Box<T>(val value: T) {
  fun <R> map(transform: (T) -> R): Box<R> = Box(transform(value))
  fun describe(prefix: String = "Box", suffix: String = "!"): String = "$prefix($value)$suffix"
}

fun <T> wrap(value: T): Box<T> = Box(value)

fun String.shout(times: Int = 1): String = uppercase() + "!".repeat(times)

val String.doubleLength: Int get() = length * 2

class Reporter(val name: String, val count: Int, private val hidden: Boolean = true) {
  fun String.tag(): String = "[$name:$this]"
  val Int.doubled: Int get() = this * 2

  fun report(): String = "original"
  fun format(): String = "original"
  fun dump(): String = "original"
}

open class Base(val id: Int)

class Derived(id: Int, val label: String) : Base(id) {
  fun dump(): String = "original"
}

private fun check(actual: Any?, expected: Any?): String? =
  if (actual == expected) null else "expected <$expected> but was <$actual>"

fun box(): String {
  val reporter = Reporter("r", 3)
  val failures = listOfNotNull(
    check(reporter.report(), "42 Box(21)! Box(21)? HI! HI!!! 6 [r:x] 10 w"),
    check(reporter.format(), "[r:x]8 Box(v)? 2"),
    check(reporter.dump(), "name=r;count=3;hidden=true;"),
    check(Derived(1, "d").dump(), "label id:2 "),
  )
  return if (failures.isEmpty()) "OK" else failures.joinToString("\n")
}
