// Captures a template that uses many language constructs and checks the model survives an encode/decode round trip.
import boberc.*
import boberc.model.FunctionTemplate
import boberc.model.TType

@Template
fun <T : Comparable<T>> This.kitchenSink(
    @Splice prefix: String,
    @Splice limit: Int,
    items: List<T>,
    vararg extra: String,
    separator: String = ", ",
): String {
    val sorted = items.sorted()
    var count = 0
    val result = buildString {
        append(prefix)
        for (item in sorted) {
            if (count >= limit) break
            if (count > 0) append(separator)
            append(item.toString())
            count++
        }
    }
    val kind = when {
        count == 0 -> "empty"
        count < limit -> "partial"
        else -> "full"
    }
    val safe = try {
        extra.joinToString().ifEmpty { null } ?: "none"
    } catch (e: IllegalStateException) {
        throw IllegalArgumentException("unexpected", e)
    } finally {
        count += 0
    }
    var i = 0
    do {
        i++
    } while (i < 3)
    val self = this as Any
    val isString = self is String
    return "$result|$kind|$safe|$i|${self.hashCode() != 0 || !isString}|${listOf(*extra).size}|${Char(65)}|${1.5f}|${2L}"
}

fun box(): String {
    // Generic templates are referenced by name: Kotlin cannot infer `T` for `This::kitchenSink`.
    val t: FunctionTemplate = template("kitchenSink")
    if (t.receiverType !is TType.This) return "Fail: receiver ${t.receiverType}"
    if (t.spliceParameters.map { it.name } != listOf("prefix", "limit")) return "Fail: splices ${t.spliceParameters.map { it.name }}"
    if (t.regularParameters.map { it.name } != listOf("items", "extra", "separator")) return "Fail: params"
    if (t.regularParameters[1].varargElementType == null) return "Fail: vararg"
    if (t.regularParameters[2].defaultValue == null) return "Fail: default value"
    if (t.typeParameters.single().bounds.size != 1) return "Fail: bounds"

    val rendered = t.render()
    val roundTrip = FunctionTemplate.decode(t.encode()).render()
    if (rendered != roundTrip) return "Fail: round trip\n$rendered\n---\n$roundTrip"

    // Spot-check symbolic references to the standard library.
    val expectedFragments = listOf(
        "(callable \"kotlin.text\" _ \"buildString\" FUNCTION",
        "(callable \"kotlin.collections\" _ \"sorted\" FUNCTION \"1|ekotlin/collections/Iterable<#T>\")",
        "(builtin \"less\"",
        "(lambda",
        "(try",
        "(doWhile",
        "(typeOp INSTANCEOF",
    )
    val missing = expectedFragments.filter { it !in rendered }
    return if (missing.isEmpty()) "OK" else "Fail: missing $missing in\n$rendered"
}
