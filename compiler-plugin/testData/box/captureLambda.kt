// Template lambdas: parameters come from the lambda, captured plugin values become pre-bound splices.
import boberc.*
import boberc.model.FunctionTemplate
import boberc.model.TType

fun makeTemplate(prefix: String, times: Int): FunctionTemplate =
  template { self: This, other: This -> prefix.repeat(times) + self.hashCode().compareTo(other.hashCode()) }

fun box(): String {
  val t = makeTemplate("ab", 2)
  if (!t.isLambda) return "Fail: not a lambda"
  if (t.regularParameters.map { it.name } != listOf("self", "other")) return "Fail: params ${t.regularParameters.map { it.name }}"
  if (t.regularParameters.any { it.type != TType.This(false) }) return "Fail: param types"
  if (t.spliceParameters.map { it.name } != listOf("prefix", "times")) return "Fail: captures ${t.spliceParameters.map { it.name }}"
  if (t.boundSplices != mapOf("prefix" to "ab", "times" to 2)) return "Fail: bound ${t.boundSplices}"
  // As a generated member, the leading `This` parameter is the dispatch receiver.
  if (t.signatureParameters(asMember = true).map { it.name } != listOf("other")) return "Fail: signature"
  if (!t.requiresOwnerClass) return "Fail: requiresOwnerClass"

  // Values are captured per evaluation of `template { }`.
  if (makeTemplate("x", 1).boundSplices["prefix"] != "x") return "Fail: captured value not rebound"

  val instance = t.instantiate("compareTo")
  if (instance.splices != mapOf("prefix" to "ab", "times" to 2)) return "Fail: instance splices ${instance.splices}"

  // A lambda without parameters or captures.
  val constant = template { 42 }
  if (constant.parameters.isNotEmpty() || constant.returnType != TType.Class("kotlin/Int", emptyList(), false)) {
    return "Fail: constant\n" + constant.render()
  }
  return "OK"
}
