package boberc.example

import boberc.Splice
import boberc.Template
import boberc.This

/*
 * Templates: plain Kotlin describing the declarations this plugin generates.
 * They are captured by the boberc compiler plugin when *this* module is compiled.
 */

/** `fun greet(name: String = "stranger"): String`, generated into `@Greeter` classes. */
@Template
fun This.greet(@Splice className: String, name: String = "stranger"): String =
    "Hello, $name! I am $className."

/** Uses the receiver (the generated member's `this`), a lambda with receiver and a stdlib inline function. */
@Template
fun This.introduce(@Splice excitement: Int): String = buildString {
    append(this@introduce.toString())
    append(" says hi")
    repeat(excitement) { append('!') }
}

/** Generic template: a type parameter, a constructor call, a `while` loop and mutable locals. */
@Template
fun <T> This.replicate(item: T, times: Int): List<T> {
    val result = ArrayList<T>(times)
    var i = 0
    while (i < times) {
        result.add(item)
        i++
    }
    return result
}

/** `This` in parameter and return types: generated as `fun tap(block: (Owner) -> Unit): Owner`. */
@Template
fun This.tap(block: (This) -> Unit): This {
    block(this)
    return this
}

/** Top-level template: ranges, `map` with a lambda, `when`, string conversion. */
@Template
fun fizzBuzz(n: Int): List<String> = (1..n).map {
    when {
        it % 15 == 0 -> "FizzBuzz"
        it % 3 == 0 -> "Fizz"
        it % 5 == 0 -> "Buzz"
        else -> it.toString()
    }
}

/** Top-level extension template with a splice; `try`/`catch` and a nullable type. */
@Template
fun String.shout(@Splice suffix: String): String? = try {
    if (isEmpty()) null else uppercase() + suffix
} catch (e: IllegalStateException) {
    null
}

/**
 * Exercises loops, `when` with a subject, safe calls and elvis, varargs with spread, enum entries, objects,
 * type checks, and overloads that differ only by type arguments (`sumOf` with `Int` vs `Long` selectors).
 */
@Template
fun summarize(vararg words: String?): String {
    val counts = mutableMapOf<Int, Int>()
    for (w in words) {
        val length = w?.length ?: -1
        counts[length] = (counts[length] ?: 0) + 1
    }
    var attempts = 0
    do {
        attempts++
    } while (attempts < 2)
    val level = when (words.size) {
        0 -> DeprecationLevel.HIDDEN
        1 -> DeprecationLevel.ERROR
        else -> DeprecationLevel.WARNING
    }
    val any: Any = level
    val isEnum = any is Enum<*>
    val total = listOf(*words).filterNotNull().sumOf { it.length }
    val totalLong = listOf(*words).filterNotNull().sumOf { it.length.toLong() * 2L }
    return "counts=$counts attempts=$attempts level=${level.name} enum=$isEnum total=$total/$totalLong unit=$Unit"
}

/** Java static fields and class literals. */
@Template
fun platformInfo(): String {
    val out = System.out
    val type = String::class
    return "${out != null}:${type.java.name}"
}
