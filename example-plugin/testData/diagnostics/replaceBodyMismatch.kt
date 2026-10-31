// RUN_PIPELINE_TILL: FIR2IR
// A template that doesn't fit the user's function is reported on that function.

// FILE: annotations.kt
package boberc.example

annotation class Stub

// FILE: main.kt
package test

import boberc.example.Stub

// Gets `{ a: Int, b: Int -> a + b }`, but `a` is a String.
<!TEMPLATE_NOT_APPLICABLE!>@Stub
fun addStrings(a: String, b: Int): Int = 0<!>

// Gets `{ -> "stubbed ..." }`, which takes no parameters: fine, they are just not used.
@Stub
fun motto(times: Int): String = ""
