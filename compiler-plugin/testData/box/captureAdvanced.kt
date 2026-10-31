// Extension members, named arguments, explicit mirror type arguments and property loops are captured.
import boberc.*

@Mirror("com.example.Box")
interface BoxMirror<T> {
    fun describe(prefix: String = "Box", suffix: String = "!"): String
    fun <R> map(transform: (T) -> R): BoxMirror<R>
}

@Mirror
interface Tagger {
    fun String.tag(): String
}

@Template
fun Tagger.advanced(box: BoxMirror<Int>): String {
    val parts = mutableListOf(box.describe(suffix = "?"), box.describe("a", "b"), "x".tag())
    parts += box.map { it.toString() }.describe()
    parts += box.callMember<String>("describe", arg("prefix", "p"))
    parts += "s".callExtension<String>("com.example.shout", 2)
    parts += callMemberExtension<String>("tag", "y")
    forEachProperty { name, value -> parts += "$name=$value" }
    return parts.joinToString()
}

fun box(): String {
    val compact = template(Tagger::advanced).encode()
    val expected = listOf(
        // omitted default -> remaining arguments passed by name
        "(dynamic CALL_MEMBER (const STRING \"describe\" (class \"kotlin/String\")) (get (param 0)) _ () ((namedArg (const STRING \"suffix\"",
        // all arguments given -> positional
        "(dynamic CALL_MEMBER (const STRING \"describe\" (class \"kotlin/String\")) (get (param 0)) _ () ((const STRING \"a\"",
        // mirror member extension: dispatch receiver + extension receiver
        "(dynamic CALL_MEMBER (const STRING \"tag\" (class \"kotlin/String\")) (get receiver) (const STRING \"x\"",
        // explicit type argument of a generic mirror function
        "(dynamic CALL_MEMBER (const STRING \"map\" (class \"kotlin/String\")) (get (param 0)) _ ((class \"kotlin/String\"))",
        "(namedArg (const STRING \"prefix\"",
        "(dynamic CALL_FUNCTION (const STRING \"com.example.shout\" (class \"kotlin/String\")) (const STRING \"s\"",
        "(dynamic CALL_MEMBER (const STRING \"tag\" (class \"kotlin/String\")) (get receiver) (const STRING \"y\"",
        "(forEachProperty (get receiver) ",
    )
    val missing = expected.filter { it !in compact }
    return if (missing.isEmpty()) "OK" else "Fail: missing $missing in\n${template(Tagger::advanced).render()}"
}
