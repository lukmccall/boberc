// RUN_PIPELINE_TILL: FIR2IR
import boberc.*

fun computeName(): String = "x"

@Template
fun This.nonConstantName(): Int = getMember(<!UNSUPPORTED_IN_TEMPLATE!>computeName()<!>)

fun capturedName(name: String) = template { self: This -> self.getMember<Int>(name) }
