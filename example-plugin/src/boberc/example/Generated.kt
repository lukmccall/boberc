package boberc.example

import boberc.Dynamic
import boberc.ParameterSplice
import boberc.Shape
import boberc.This
import boberc.callMember
import boberc.forEachParameter
import boberc.fir.ShapeParameter
import boberc.ir.IrTemplates
import boberc.ir.functionRef
import boberc.mapParameters
import boberc.plugin.bobercPlugin
import boberc.shape
import boberc.template
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.util.functions

/*
 * Declarations the `bobercPlugin {}` DSL generates from shapes, without a hand-written FIR or IR extension:
 *
 *  - `@Registered class Point` gets a top-level `object boberc.generated.PointInfo { val typeName = "Point" }`
 *    (`topLevelClass`), only for non-generic classes (`where`), and its `registeredAs()` is filled in IR by an
 *    `ir { }` block;
 *  - every `@WithSibling fun greet(name: String): String` gets an overload `greet(prefix: String, name: String)`
 *    (`sibling`), and the original delegates to exactly that overload (`generatedSibling`), even when several
 *    functions share its name.
 */

/** The object generated for each registered class: public, like the shape. Member bodies are ignored (filled in IR). */
@Shape
object RegistrationShape {
  val typeName: String = ""
}

/** The sibling: a `prefix` first, then the original's parameters; `R` is the original's return type. */
@Shape
private abstract class PrefixedShape<R> {
  abstract fun prefixed(prefix: String, vararg parameters: ParameterSplice): R
}

internal object SiblingKey : GeneratedDeclarationKey() {
  override fun toString(): String = "Sibling"
}

val GeneratedFeature = bobercPlugin {
  classesAnnotatedWith("boberc.example.Registered", where = { !it.isGeneric }) {
    topLevelClass("boberc.generated", { "${it.simpleName}Info" }, shape(RegistrationShape::class)) {
      property("typeName") {
        val typeName = className
        template { -> typeName }
      }
    }
    ir {
      val registeredAs = owner.functions.single { it.name.asString() == "registeredAs" }
      val name = classInfo.fqName
      IrTemplates.replaceBody(registeredAs, template { -> "registered as $name" }, pluginContext)
    }
  }

  functionsAnnotatedWith("boberc.example.WithSibling") {
    sibling(
      shape(PrefixedShape::class), "prefixed", SiblingKey,
      typeArguments = { listOf(original.resolvedReturnType) },
      parameters = { original.valueParameterSymbols.map { ShapeParameter(it.name, it.resolvedReturnType) } },
      // The sibling joins all of its arguments, and names the function it was generated for.
      body = {
        val from = generatedFrom!!.parameters.count { it.kind == IrParameterKind.Regular }
        template { ->
          buildString {
            forEachParameter { p -> append(p.value).append(' ') }
            append("(from $from)")
          }
        }
      },
    )
    // The original calls exactly its own sibling, not whichever overload resolution would pick.
    replaceBody {
      val sibling = functionRef(generatedSibling(SiblingKey)!!)
      template { self: This -> self.callMember<Dynamic>(sibling, "Hello", mapParameters { p -> p.value }) }
    }
  }
}
