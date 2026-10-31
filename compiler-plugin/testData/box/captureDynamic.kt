// Mirrors and dynamic accesses are captured as late-bound (by-name) accesses.
import boberc.*
import boberc.model.TExpr
import boberc.model.TType

@Mirror
interface HasCounter {
    var counter: Int
    fun bump(by: Int): Int
}

@Mirror("com.example.Point")
interface PointMirror {
    val x: Int
}

@Template
fun HasCounter.touch(point: PointMirror): String {
    counter = counter + bump(1)
    val other = newInstance("com.example.Point", 1) as PointMirror
    return "${point.x + other.x} ${getMember<String>("label")} ${objectInstance("com.example.Registry").isInstanceOf("com.example.Base")}"
}

fun box(): String {
    val t = template(HasCounter::touch)
    // The unnamed mirror stands for the owner class, the named one for its class.
    if (t.receiverType != TType.This(false)) return "Fail: receiver ${t.receiverType}"
    if (t.regularParameters.single().type != TType.Named("com.example.Point", emptyList(), false)) return "Fail: param type"
    val rendered = t.render()
    val compact = t.encode()
    val expected = listOf(
        "(dynamic SET_MEMBER (const STRING \"counter\"",
        "(dynamic GET_MEMBER (const STRING \"counter\"",
        "(dynamic CALL_MEMBER (const STRING \"bump\"",
        "(dynamic NEW_INSTANCE (const STRING \"com.example.Point\"",
        "(typeOp CAST (named \"com.example.Point\")",
        "(dynamic GET_MEMBER (const STRING \"label\"",
        "(dynamic GET_OBJECT (const STRING \"com.example.Registry\"",
        "(dynamic IS_INSTANCE (const STRING \"com.example.Base\"",
    )
    val missing = expected.filter { it !in compact }
    if (missing.isNotEmpty()) return "Fail: missing $missing in\n$rendered"
    // Nothing from the plugin module (the mirror interfaces) leaks into the model.
    if ("HasCounter" in rendered || "PointMirror" in rendered) return "Fail: mirror leaked\n$rendered"
    return "OK"
}
