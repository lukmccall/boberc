// End-to-end: templates reading/writing members and declarations that the plugin module cannot see.

// FILE: main.kt
package test

class Point(val x: Int, val y: Int)

object Registry {
  val name = "registry"
}

fun helper(a: Int): Int = a + 100

val greeting = "hi"

class X {
  val x = 10
  private val secret = "s3cr3t"
  var counter = 0

  fun twice(n: Int): Int = n * 2

  fun toReplace(): String = "original"
  fun viaMirror(): Int = 0
  fun pointSum(point: Point): Int = 0
}

class Account(val id: Int, val owner: String) {
  fun describe(): String = "original"
}

class NoId(val owner: String) {
  fun describe(): String = "original"
}

private fun check(actual: Any?, expected: Any?): String? =
  if (actual == expected) null else "expected <$expected> but was <$actual>"

fun box(): String {
  val x = X()
  val failures = listOfNotNull(
    check(
      x.toReplace(),
      "x=10 secret=s3cr3t twice=20 counter=1 point.y=32 isPoint=true isX=false registry=registry helper=110 greeting=hi",
    ),
    check(x.counter, 1),
    check(x.toReplace().contains("counter=2"), true),
    check(x.viaMirror(), 32),
    check(x.pointSum(Point(3, 4)), 7),
    check(x.makePoint(5).let { "${it.x},${it.y}" }, "10,5"),
    check(x.pointProperties(), "Point(x, y)"),
    check(x.registryName(), "registry"),
    check(Account(7, "ann").describe(), "Account(id, owner): id=7"),
    check(NoId("bob").describe(), "original"),
  )
  return if (failures.isEmpty()) "OK" else failures.joinToString("\n")
}
