package boberc.example

import boberc.Dynamic
import boberc.Mirror
import boberc.Shape
import boberc.call
import boberc.forEachProperty
import boberc.ir.constructorRef
import boberc.plugin.bobercPlugin
import boberc.shape
import boberc.template
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.ir.util.primaryConstructor

/*
 * A plugin feature written with shapes (FIR) and templates (IR); no FIR or IR declaration is built by hand.
 *
 * For every `@Described class Point(val x: Int, val y: Int)`:
 *
 * ```
 * class Point : Describable                              supertype added       (DescribableShape)
 * ├── private class Describer : Describer<Point>         nested class          (DescriberShape)
 * │   ├── typeName = "Point"                             IR: field initializer (template)
 * │   └── describe(value) = "Point(x=1, y=2)"            IR: body              (template, forEachProperty)
 * └── companion object                                   the user's, or generated (DescribedCompanionShape)
 *     └── val describer: Describer<Point> = Describer()  IR: field initializer (template, constructorRef)
 * ```
 */

@Mirror("boberc.example.Describable")
internal interface DescribableMirror

@Mirror("boberc.example.Describer")
internal interface DescriberMirror<T> {
  val typeName: String
  fun describe(value: T): String
}

/** Added to every `@Described` class. */
@Shape
private interface DescribableShape : DescribableMirror

/** The nested describer; `T` is the described class. Abstract members are generated final, bodies come from IR. */
@Shape
private abstract class DescriberShape<T> : DescriberMirror<T> {
  abstract override val typeName: String
  abstract override fun describe(value: T): String
}

/** Members added to the companion; the private constructor is used when the companion is generated. */
@Shape
private abstract class DescribedCompanionShape<T> private constructor() {
  abstract val describer: DescriberMirror<T>
}

internal object DescribedKey : GeneratedDeclarationKey() {
  override fun toString(): String = "Described"
}

private const val DESCRIBER_CLASS = "Describer"

/** The whole feature: where each shape goes, and the templates of the bodies. */
val DescribedFeature = bobercPlugin {
  classesAnnotatedWith("boberc.example.Described", where = { it.isClass && !it.isGeneric }) {
    supertypes(shape(DescribableShape::class))

    nestedClass(DESCRIBER_CLASS, shape(DescriberShape::class), DescribedKey, typeArguments = { listOf(ownerType) }) {
      property("typeName") {
        val typeName = className
        template { -> typeName }
      }
      function("describe") {
        val typeName = className
        template { value: Dynamic ->
          buildString {
            append(typeName)
            append("(")
            var first = true
            value.forEachProperty { name, propertyValue ->
              if (!first) append(", ")
              first = false
              append("$name=$propertyValue")
            }
            append(")")
          }
        }
      }
    }

    companion(shape(DescribedCompanionShape::class), DescribedKey, typeArguments = { listOf(ownerType) }) {
      property("describer") {
        val newDescriber = constructorRef(nestedClass(DESCRIBER_CLASS)!!.primaryConstructor!!)
        template { -> newDescriber.call<Dynamic>() }
      }
    }
  }
}
