// RUN_PIPELINE_TILL: FIR2IR
import boberc.*

class NotAShape

val notShape = <!NOT_A_SHAPE_REFERENCE!>shape(NotAShape::class)<!>

class PluginModuleType

@Shape
abstract class UsesPluginType {
  <!UNSUPPORTED_IN_TEMPLATE!>abstract val value: PluginModuleType<!>
}

@Shape
abstract class GenericMember {
  <!UNSUPPORTED_IN_TEMPLATE!>abstract fun <T> generic(value: T): T<!>
}

@Shape
abstract class WithDefault {
  abstract fun withDefault(<!UNSUPPORTED_IN_TEMPLATE!>x: Int = 1<!>): Int
}

@Shape
abstract class Nested {
  <!UNSUPPORTED_IN_TEMPLATE!>class Inner<!>
}

@Template
fun temporaryVar(x: Int): Int {
  <!UNSUPPORTED_IN_TEMPLATE!>var notVal = temporary(x)<!>
  return notVal
}

@Template
fun temporaryOutsideVal(x: Int): Int = <!UNSUPPORTED_IN_TEMPLATE!>temporary(x)<!> + 1
