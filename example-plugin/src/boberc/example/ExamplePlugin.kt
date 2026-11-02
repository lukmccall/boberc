package boberc.example

import boberc.Dynamic
import boberc.This
import boberc.call
import boberc.construct
import boberc.ir.functionRef
import boberc.mirror
import boberc.targetFunction
import boberc.forEachParameter
import boberc.mapParameters
import boberc.arg
import boberc.callExtension
import boberc.callMemberExtension
import boberc.forEachProperty
import boberc.getExtension
import boberc.getMemberExtension
import boberc.callFunction
import boberc.callMember
import boberc.getMember
import boberc.getProperty
import boberc.isInstanceOf
import boberc.newInstance
import boberc.objectInstance
import boberc.setMember
import boberc.plugin.bobercPlugin
import boberc.template
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.ir.types.isString

/**
 * The whole plugin: which templates go where. No FIR or IR code is written by hand.
 *
 * `template(...)` calls are replaced at compile time (by boberc) with the captured template models.
 */
val ExamplePlugin = bobercPlugin {
  classesAnnotatedWith("boberc.example.Greeter") {
    // Splice values are computed per class during FIR generation.
    function(template(This::greet)) { mapOf("className" to className) }
    function(template(This::introduce)) { mapOf("excitement" to 3) }
    // Generic templates are referenced by name (Kotlin can't infer `T` for `This::replicate`).
    function(template("replicate"))
    function(template(This::tap))
    // A new member from a lambda; `n` is captured, i.e. spliced per class.
    function("shout") {
      val n = className
      template { self: This, text: String -> "$n says ${text.uppercase()}" }
    }
    // Redefine an existing, user-written member: its body is replaced.
    replaceBody("secret") {
      val owner = className
      template { -> "redefined in $owner" }
    }
  }

  // Implement an open inherited member.
  classesAnnotatedWith("boberc.example.AutoToString") {
    implement("toString") {
      val name = className
      template { self: This -> "$name#${self.hashCode()}" }
    }
  }

  // Implement an abstract inherited member: `Comparable<T>.compareTo(other: T)` with `T` = the class itself.
  classesAnnotatedWith("boberc.example.HashOrdered") {
    implement("compareTo", template { self: This, other: This -> self.hashCode().compareTo(other.hashCode()) })
  }

  // Access members the plugin module can't see: by name, or through typed mirrors.
  classNamed("test.X") {
    replaceBody("toReplace", template { self: This ->
      self.setMember("counter", self.getMember<Int>("counter") + 1)
      val x = self.getMember<Int>("x")
      val point = newInstance("test.Point", x, 32)
      "x=$x secret=${self.getMember<String>("secret")} twice=${self.callMember<Int>("twice", x)} " +
          "counter=${self.getMember<Int>("counter")} point.y=${point.getMember<Int>("y")} " +
          "isPoint=${point.isInstanceOf("test.Point")} isX=${self.isInstanceOf("test.Point")} " +
          "registry=${objectInstance("test.Registry").getMember<String>("name")} " +
          "helper=${callFunction<Int>("test.helper", x)} greeting=${getProperty<String>("test.greeting")}"
    })
    // Typed access: `HasX` is bound to `X`, `PointMirror` to `test.Point`.
    replaceBody("viaMirror", template { self: HasX -> self.x * 3 + self.twice(1) })
    replaceBody("pointSum", template { point: PointMirror -> point.x + point.y })
    // A generated member whose signature uses a named mirror: `fun makePoint(y: Int): test.Point`.
    function("makePoint", template { self: HasX, y: Int -> construct<PointMirror>(self.x, y) })
    // An object through its mirror.
    function("registryName", template { -> mirror<RegistryMirror>().name })
    // Look classes up by name while generating (FIR) and splice what was found.
    function("pointProperties") {
      val names = findClass("test.Point")?.propertyNames.orEmpty().joinToString()
      template { -> "Point($names)" }
    }
  }

  // Select classes by structure, then read a property chosen by the plugin (its name is a captured value).
  classesMatching({ it.hasProperty("id") && it.hasFunction("describe") }) {
    replaceBody("describe") {
      val info = classInfo!!
      val simpleName = info.simpleName
      val properties = info.propertyNames.joinToString()
      val first = info.propertyNames.first()
      template { self: This -> "$simpleName($properties): $first=${self.getMember<Any?>(first)}" }
    }
  }

  // Generic members/classes (inferred type arguments), extension members, default and named arguments.
  classNamed("test.Reporter") {
    replaceBody("report", template { self: This ->
      val box = newInstance("test.Box", 21)                                  // Box<Int>, inferred
      val doubled = box.callMember<Dynamic>("map", { v: Int -> v * 2 })      // <R> inferred as Int
      val wrapped = callFunction<Dynamic>("test.wrap", "w")                   // generic top-level function
      listOf(
        doubled.getMember<Int>("value"),
        box.callMember<String>("describe"),                                // both defaults
        box.callMember<String>("describe", arg("suffix", "?")),            // named argument
        "hi".callExtension<String>("test.shout"),                          // top-level extension, default
        "hi".callExtension<String>("test.shout", 3),
        "abc".getExtension<Int>("test.doubleLength"),                      // extension property
        self.callMemberExtension<String>("tag", "x"),                      // member extension
        self.getMemberExtension<Int>("doubled", 5),
        wrapped.getMember<String>("value"),
      ).joinToString(" ")
    })
    // The same through typed mirrors: generic mirror, explicit/inferred type arguments, omitted defaults.
    replaceBody("format", template { self: Tagger ->
      @Suppress("UNCHECKED_CAST")
      val box = newInstance("test.Box", "v") as BoxMirror<String>
      with(self) { "x".tag() + 4.doubled } + " " + box.describe(suffix = "?") + " " + box.map { it.length + 1 }.value
    })
    // Compile-time loop over the properties (private ones are accessible from inside the class).
    replaceBody("dump", template { self: This ->
      buildString { self.forEachProperty { name, value -> append(name).append('=').append(value).append(';') } }
    })
  }
  classNamed("test.Derived") {
    // Inherited properties are included; `value` has each property's real type.
    replaceBody("dump", template { self: This ->
      buildString {
        self.forEachProperty { name, value ->
          if (value is Int) append(name).append(':').append(value + 1).append(' ') else append(name).append(' ')
        }
      }
    })
  }

  // Implement `external` functions of any arity by delegating to a same-name overload (the kolibri pattern):
  // a leading `self: This` binds the receiver, parameters are forwarded/inspected at compile time.
  functionsAnnotatedWith("boberc.example.Delegated") {
    // One template for every function: `targetFunction` is the function it is applied to.
    replaceBody(template { self: This ->
      if (targetFunction.hasAnnotation(Deprecated::class)) {
        self.callMember<Unit>("log", "deprecated:${targetFunction.name}/${targetFunction.parameterCount}")
      }
      val result = self.callMember<Dynamic>(
        targetFunction.name,
        self.getMember<String>("prefix"),
        mapParameters { p ->
          // Compile-time `if`: for an Int parameter the String branch is never materialized.
          if (p.hasAnnotation(UPPER)) p.value.callExtension<String>("kotlin.text.uppercase") else p.value
        },
      )
      forEachParameter { p -> if (p.isSubtypeOf(Int::class)) self.callMember<Unit>("log", "${targetFunction.name}:${p.name}") }
      result
    })
  }

  // The plugin picks the exact functions to call (here: found in IR) and hands them to the template.
  functionsAnnotatedWith("boberc.example.Forward") {
    replaceBody {
      val impl = functionRef(function.parentAsClass.functions.single { it.name.asString() == "${functionName}Impl" })
      val record = functionRef(
        pluginContext.finderForBuiltins().findFunctions(CallableId(FqName("test"), Name.identifier("record"))).single().owner
      )
      template { self: This ->
        record.call<Unit>(targetFunction.name)
        self.callMember<Dynamic>(impl, mapParameters { p -> p.value })
      }
    }
  }

  // Redefine annotated functions, choosing the template per function.
  functionsAnnotatedWith("boberc.example.Stub") {
    replaceBody {
      val name = functionName
      when {
        name.startsWith("add") -> template { a: Int, b: Int -> a + b }
        function.returnType.isString() -> template { -> "stubbed $name" }
        else -> null // keep the original body
      }
    }
  }
  topLevelFunction("boberc.example.generated", template(::fizzBuzz))
  topLevelFunction("boberc.example.generated", template(String::shout), splices = mapOf("suffix" to "!!!"))
  topLevelFunction("boberc.example.generated", template(::summarize))
  topLevelFunction("boberc.example.generated", template(::platformInfo))
}

class ExamplePluginRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String get() = PLUGIN_ID
  override val supportsK2: Boolean get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    ExamplePlugin.registerExtensions(this)
    // A feature written with shapes + templates and registered by hand (see Described.kt).
    FirExtensionRegistrarAdapter.registerExtension(DescribedFirRegistrar())
    IrGenerationExtension.registerExtension(DescribedIrGeneration())
  }
}

@Suppress("unused") // Used via reflection.
class ExampleCommandLineProcessor : CommandLineProcessor {
  override val pluginId: String get() = PLUGIN_ID
  override val pluginOptions: Collection<CliOption> get() = emptyList()

  override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
    error("Unexpected config option: '${option.optionName}'")
  }
}

private const val PLUGIN_ID = "boberc.example"

/** A `const val` used inside a template: inlined when the template is captured. */
private const val UPPER = "boberc.example.Upper"
