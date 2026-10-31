// RUN_PIPELINE_TILL: FIR2IR
// `Box.describe` has no `suffix` parameter: the named argument in `report` and the mirror call in `format`
// can't be resolved. `dump` (a property loop) still applies.

// FILE: main.kt
package test

class Box<T>(val value: T) {
    fun <R> map(transform: (T) -> R): Box<R> = Box(transform(value))
    fun describe(prefix: String = "Box"): String = prefix
}

fun <T> wrap(value: T): Box<T> = Box(value)

fun String.shout(times: Int = 1): String = this

val String.doubleLength: Int get() = 0

class Reporter {
    fun String.tag(): String = this
    val Int.doubled: Int get() = this

    <!TEMPLATE_NOT_APPLICABLE!>fun report(): String = ""<!>
    <!TEMPLATE_NOT_APPLICABLE!>fun format(): String = ""<!>
    fun dump(): String = ""
}
