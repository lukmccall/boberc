// RUN_PIPELINE_TILL: FIR2IR
// The example plugin redefines `test.X.toReplace` using `x`, `secret`, `counter`... which this `X` lacks, and
// generates `makePoint` (reading `x`) into it. Both are reported on user code instead of crashing the compiler.

// FILE: main.kt
package test

class Point(val x: Int, val y: Int)

<!TEMPLATE_NOT_APPLICABLE!>class X {
    <!TEMPLATE_NOT_APPLICABLE!>fun toReplace(): String = "original"<!>
}<!>
