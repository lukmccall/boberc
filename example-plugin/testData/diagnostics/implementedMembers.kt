// RUN_PIPELINE_TILL: FRONTEND
// Members implemented by the plugin satisfy the frontend: no ABSTRACT_MEMBER_NOT_IMPLEMENTED for `Version`.

// FILE: annotations.kt
package boberc.example

annotation class HashOrdered

// FILE: main.kt
package test

import boberc.example.HashOrdered

@HashOrdered
class Version(val number: Int) : Comparable<Version>

<!ABSTRACT_MEMBER_NOT_IMPLEMENTED!>class NotOrdered<!>(val number: Int) : Comparable<NotOrdered>

fun test(a: Version, b: Version): Boolean = a < b
