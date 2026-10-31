// RUN_PIPELINE_TILL: FIR2IR
import boberc.*

fun helperFromPluginModule(): Int = 42

@Template
fun usesPluginModuleHelper(): Int = <!UNSUPPORTED_IN_TEMPLATE!>helperFromPluginModule()<!> + 1

@Template
fun usesCallableReference(items: List<String>): List<Int> = items.map(<!UNSUPPORTED_IN_TEMPLATE!>String::length<!>)

@Template
fun <T> generic(value: T): T = value

fun notATemplate(): Int = 0

val notTemplate = <!NOT_A_TEMPLATE_REFERENCE!>template(::notATemplate)<!>

val unknownName = <!TEMPLATE_NOT_FOUND!>template("doesNotExist")<!>

val genericByName = template("generic")
