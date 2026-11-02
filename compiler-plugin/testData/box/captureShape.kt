// Captures @Shape classes and checks the rendered shape models.
import boberc.*

@Mirror("com.example.Codec")
interface CodecMirror<T> {
  val name: String
  fun encode(value: T, out: StringBuilder)
}

@Shape
private abstract class CodecShape<T> : CodecMirror<T> {
  abstract override val name: String
  abstract override fun encode(value: T, out: StringBuilder)
  abstract var hits: Int
  open fun helper(vararg values: T): List<T>? = null
  private external fun native(pointer: Long, vararg rest: ParameterSplice): T
}

@Shape
interface HintShape<M> {
  fun module(): M
  val self: This
}

@Shape
private abstract class CompanionShape<T> private constructor(seed: Int) {
  abstract val codec: CodecMirror<T>
}

private val EXPECTED_CODEC = """
(shape 8 "CodecShape" CLASS PRIVATE false
  ((typeParam "T" INVARIANT false ()))
  ((named "com.example.Codec" (invariant (typeParam 0))))
  ((ctor PUBLIC true ()))
  (
    (property "name" PUBLIC ABSTRACT true (class "kotlin/String") false)
    (function "encode" PUBLIC ABSTRACT true false false false _
      (
        (param "value" (typeParam 0) false _ false false _)
        (param "out" (class "java/lang/StringBuilder") false _ false false _))
      (class "kotlin/Unit"))
    (property "hits" PUBLIC ABSTRACT false (class "kotlin/Int") true)
    (function "helper" PUBLIC OPEN false false false false _
      (
        (param "values"
          (class "kotlin/Array" (out (typeParam 0)))
          false
          (typeParam 0)
          false
          false
          _))
      (class? "kotlin/collections/List" (invariant (typeParam 0))))
    (function "native" PRIVATE FINAL false false false true _
      (
        (param "pointer" (class "kotlin/Long") false _ false false _)
        (param "rest" (class "boberc/ParameterSplice") true _ false false _))
      (typeParam 0))))
""".trim()

private val EXPECTED_HINT = """
(shape 8 "HintShape" INTERFACE PUBLIC false
  ((typeParam "M" INVARIANT false ()))
  ()
  ()
  (
    (function "module" PUBLIC ABSTRACT false false false false _ () (typeParam 0))
    (property "self" PUBLIC ABSTRACT false (this) false)))
""".trim()

private val EXPECTED_COMPANION = """
(shape 8 "CompanionShape" CLASS PRIVATE false
  ((typeParam "T" INVARIANT false ()))
  ()
  ((ctor PRIVATE true ((param "seed" (class "kotlin/Int") false _ false false _))))
  (
    (property "codec" PUBLIC ABSTRACT false
      (named "com.example.Codec" (invariant (typeParam 0)))
      false)))
""".trim()

fun box(): String {
  val codec = shape(CodecShape::class)
  val hint = shape(HintShape::class)
  val companion = shape(CompanionShape::class)
  if (codec.memberNames != listOf("name", "encode", "hits", "helper", "native")) return "Fail: members ${codec.memberNames}"
  return when {
    codec.render() != EXPECTED_CODEC -> "Fail, codec rendered:\n${codec.render()}"
    hint.render() != EXPECTED_HINT -> "Fail, hint rendered:\n${hint.render()}"
    companion.render() != EXPECTED_COMPANION -> "Fail, companion rendered:\n${companion.render()}"
    else -> "OK"
  }
}
