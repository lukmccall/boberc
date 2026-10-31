// RUN_PIPELINE_TILL: FIR2IR
import boberc.*

class PluginState(val name: String)

fun capturesObject(state: PluginState) = template { self: This -> "I am " + <!UNSUPPORTED_IN_TEMPLATE!>state<!>.name }

fun capturesString(state: PluginState): Any {
    val name = state.name
    return template { self: This -> "I am $name" }
}
