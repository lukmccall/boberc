// RUN_PIPELINE_TILL: FIR2IR
import boberc.*

@Template
fun This.earlyReturn(): String = buildString {
  forEachProperty { name, value ->
    if (value == null) <!UNSUPPORTED_IN_TEMPLATE!>return@forEachProperty<!>
    append(name)
  }
}

@Template
fun strayArgument(): Any? = <!UNSUPPORTED_IN_TEMPLATE!>arg("x", 1)<!>

@Template
fun This.leaksParameter(): Unit = forEachParameter { p -> val copy: Any = <!UNSUPPORTED_IN_TEMPLATE!>p<!> }
