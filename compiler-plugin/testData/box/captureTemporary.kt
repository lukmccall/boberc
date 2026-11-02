// `val x = temporary(...)` is captured as a compiler temporary.
import boberc.*

@Template
fun withTemporary(x: Int): Int {
  val sum = temporary(x + 1)
  return sum * 2
}

fun box(): String {
  if (withTemporary(1) != 4) return "Fail: temporary() must return its argument outside templates"
  val rendered = template(::withTemporary).render()
  return when {
    "\"sum\"" !in rendered || "\"BOBERC_TEMPORARY\"" !in rendered -> "Fail, rendered:\n$rendered"
    "temporary" in rendered -> "Fail: the temporary() call itself was captured:\n$rendered"
    else -> "OK"
  }
}
