// RUN_PIPELINE_TILL: FIR2IR
import boberc.*

@Mirror
interface Unnamed {
    val x: Int
}

@Template
fun objectOfUnnamedMirror(): Int = <!UNSUPPORTED_IN_TEMPLATE!>mirror<Unnamed>()<!>.x

@Template
fun targetFunctionAsValue(): String {
    <!UNSUPPORTED_IN_TEMPLATE!>val target = targetFunction<!>
    return target.name
}
