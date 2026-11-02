@file:JvmName("BobercIntrinsics")

package boberc.internal

import boberc.BobercInternalApi
import boberc.model.ClassShape
import boberc.model.FunctionTemplate
import java.util.concurrent.ConcurrentHashMap

/*
 * Targets of the boberc intrinsics after lowering by the boberc compiler plugin. The models are split into
 * `parts` to stay below the JVM constant pool string limit; each distinct model is decoded once.
 */

private val templates = ConcurrentHashMap<String, FunctionTemplate>()
private val shapes = ConcurrentHashMap<String, ClassShape>()

private inline fun <T : Any> ConcurrentHashMap<String, T>.decoded(parts: Array<out String>, decode: (String) -> T): T {
  // A single part is the class-file constant itself: its hash is cached and lookups hit the identity fast path.
  val encoded = if (parts.size == 1) parts[0] else parts.joinToString(separator = "")
  return getOrPut(encoded) { decode(encoded) }
}

/** `boberc.template(::f)` and `boberc.template("name")`. */
@BobercInternalApi
public fun decodeFunctionTemplate(vararg parts: String): FunctionTemplate = templates.decoded(parts, FunctionTemplate::decode)

/**
 * `boberc.template { ... }` (a template lambda). [captured] are the values of the variables captured by the lambda,
 * in the order of the template's splice parameters.
 */
@BobercInternalApi
public fun decodeLambdaTemplate(captured: Array<Any?>, vararg parts: String): FunctionTemplate {
  val template = decodeFunctionTemplate(*parts)
  val splices = template.spliceParameters
  require(splices.size == captured.size) { "boberc: expected ${splices.size} captured values, got ${captured.size}" }
  return template.bindSplices(splices.map { it.name }.zip(captured).toMap())
}

/** `boberc.shape(X::class)`. */
@BobercInternalApi
public fun decodeClassShape(vararg parts: String): ClassShape = shapes.decoded(parts, ClassShape::decode)
