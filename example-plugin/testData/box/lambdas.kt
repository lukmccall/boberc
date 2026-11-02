// End-to-end: template lambdas used to implement inherited members and to redefine existing functions.

// FILE: annotations.kt
package boberc.example

annotation class Greeter
annotation class AutoToString
annotation class HashOrdered
annotation class Stub

// FILE: main.kt
package test

import boberc.example.*

@Greeter
class Person(val name: String) {
  fun secret(): String = "original"
}

@AutoToString
class Point(val x: Int, val y: Int) {
  override fun hashCode(): Int = x * 31 + y
}

// `compareTo` is abstract in Comparable and implemented by the plugin.
@HashOrdered
class Version(val number: Int) : Comparable<Version> {
  override fun hashCode(): Int = number
}

@Stub
fun add(a: Int, b: Int): Int = TODO("implemented by the plugin")

@Stub
fun addWithDefault(a: Int, b: Int = 10): Int = TODO()

@Stub
fun motto(): String = "original motto"

@Stub
fun untouched(): Int = 7

private fun check(actual: Any?, expected: Any?): String? =
  if (actual == expected) null else "expected <$expected> but was <$actual>"

fun box(): String {
  val versions = listOf(Version(3), Version(1), Version(2))
  val failures = listOfNotNull(
    check(Person("Ann").shout("hi"), "Person says HI"),
    check(Person("Ann").secret(), "redefined in Person"),
    check(Point(1, 2).toString(), "Point#33"),
    check("${Point(0, 5)}", "Point#5"),
    check(versions.sorted().map { it.number }, listOf(1, 2, 3)),
    check(Version(1) < Version(2), true),
    check(Version(5).compareTo(Version(5)), 0),
    check(add(2, 3), 5),
    check(addWithDefault(2), 12),
    check(motto(), "stubbed motto"),
    check(untouched(), 7),
  )
  return if (failures.isEmpty()) "OK" else failures.joinToString("\n")
}
