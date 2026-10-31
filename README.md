# boberc — write Kotlin compiler plugins in plain Kotlin

Generating code in a K2 compiler plugin normally means building the **signature in FIR** (so user code can
resolve it) and the **body in IR** by hand, with factories such as `IrCallImpl`, symbol lookups and offsets.
boberc lets you write the code you want to generate as ordinary Kotlin instead:

```kotlin
@Template
fun This.greet(@Splice className: String, name: String = "stranger"): String =
    "Hello, $name! I am $className."

val ExamplePlugin = bobercPlugin {
    classesAnnotatedWith("boberc.example.Greeter") {
        function(template(This::greet)) { mapOf("className" to className) }
    }
}
```

Every class annotated with `@Greeter` now gets a real member `fun greet(name: String = "stranger"): String`. It
is visible to the IDE and frontend, type-checked at call sites, and has a fully generated IR body. The plugin
contains no hand-written FIR or IR code.

Whole declarations work the same way. A **shape** is a plain Kotlin class whose declarations FIR generates
(a nested class, its supertypes, constructors and member signatures). Templates then fill the bodies in IR:

```kotlin
@Shape
private abstract class CodecShape<R> : CodecMirror<R> {   // R is bound per generated class
    abstract override val recordClass: Class<R>
    abstract override fun decode(reader: ReaderMirror): R
}

createNestedClassFromShape(owner, CODEC, shape(CodecShape::class).bind(recordType), Key)
```

Both real-world plugins in this workspace were ported: [expo-modules-v2-android][expo] and [kolibri][kolibri]. In
each, *every* FIR declaration and IR body is now a boberc shape or template, and the output is byte-identical
to the hand-written plugin.

> Status: proof of concept, built against Kotlin **2.4.20** (K2 only, JVM).

## How it works

```
  plugin author's module                       the plugin at work, compiling user code
 ┌────────────────────────────────┐           ┌──────────────────────────────────────────────┐
 │ @Template fun This.greet(...)  │           │  FIR: createMemberFunctionFromTemplate()     │
 │ template(This::greet)          │──model──▶ │       -> signature, visible to resolution    │
 └────────────────────────────────┘ embedded  │  IR:  IrTemplates.generateBody()             │
     compiled WITH boberc            in the   │       -> body rebuilt, symbols re-resolved   │
     (:compiler-plugin)              plugin   └──────────────────────────────────────────────┘
                                     binary          (:boberc-runtime, inside the plugin)
```

1. **Capture** (`:compiler-plugin`, the boberc compiler plugin). It runs while the *plugin author's* module
   compiles. Once the code is fully resolved, it walks each `@Template` function's IR and encodes the
   signature and body into a **template model** (`boberc.model`). Each `template(::f)` call is replaced by
   `decodeFunctionTemplate("<encoded model>")`.
2. **Symbolic references.** The model never holds compiler symbols. A call to `buildString` is stored as
   `callable kotlin.text/buildString` plus an overload signature; types are stored as class ids. That is what
   lets the model move from one compilation to another. Fake overrides are normalized to the real
   declaration.
3. **Materialization** (`:boberc-runtime`, shipped inside the generated plugin). In the target compilation:
   - **FIR:** the model's signature becomes a generated FIR function (`createMemberFunction` /
     `createTopLevelFunction` from the FIR plugin API). The declaration is keyed with a `BobercTemplateKey` that
     carries the template and its splice values.
   - **IR:** `BobercIrGenerationExtension` finds functions with that key and rebuilds the body IR, re-resolving
     every reference via `IrPluginContext.finderForBuiltins()`.

**Shapes** (`@Shape` classes) go through the same pipeline. They are captured into a `boberc.model.ClassShape`
(declarations and signatures, no bodies), `shape(X::class)` is lowered to `decodeClassShape(...)`, and the
`boberc.fir` shape functions build the FIR declarations with the same FIR plugin API builders.

Because the template is captured from IR *after* full resolution, everything the frontend desugars comes along
for free: `for` loops, string templates, `when` with a subject, elvis, safe calls, smart casts, implicit
coercions, default arguments and inferred type arguments.

## API

### Writing templates (`boberc.*`)

| API | Meaning |
| --- | --- |
| `@Template` | Marks a top-level function as a template. It is still a normal function, so you can unit-test it. |
| `This` | Stands for "the class the member is generated into". `fun This.foo()`: `this` becomes the dispatch receiver. Also usable in types: `fun This.tap(block: (This) -> Unit): This`. |
| `@Splice` | A compile-time parameter. It does not appear in the generated function; each use is replaced by a constant provided at instantiation. Allowed types: primitives and `String`, optionally nullable. |
| `template(::f)` / `template(This::f)` | Intrinsic that returns the captured `FunctionTemplate`. |
| `template { a: Int, b: Int -> a + b }` | A **template lambda** (see below). |
| `template("f")` | The same, by name. Use it for **generic** templates, because Kotlin can't infer type arguments of `::f`. Resolved at compile time, preferring the caller's package; FQ names work too. |

A template may only reference declarations that exist in the *target* compilation: the standard library, the
JDK, or a runtime library your plugin adds. Referencing declarations of the plugin module itself is a
compile-time error.

### Template lambdas

For one-off bodies, write the template inline as a lambda with explicitly typed parameters:

```kotlin
template { self: This, other: This -> self.hashCode().compareTo(other.hashCode()) }
```

- **Binding.** A leading `self: This` binds the **dispatch receiver**, whatever the function's arity. The other
  lambda parameters bind to the remaining parameters, **aligned from the end**. For `fun compareTo(other: T)`,
  `{ other: This -> ... }` binds `other`, and `{ self: This, other: This -> ... }` also binds the receiver.
  `{ -> ... }` ignores all parameters. When a lambda *generates* a new member, a leading `This` parameter
  becomes the dispatch receiver.
- **Captured values become splices.** Plugin-side locals of primitive or `String` type that the lambda
  captures are bound to their value when `template { }` is evaluated. This is the easy way to get per-class
  or per-function values:
  `implement("toString") { val name = className; template { self: This -> "$name#${self.hashCode()}" } }`.
- **Checks.** Bindings and the return type are checked when the template is applied. For `replaceBody`, a
  mismatch is reported as a `TEMPLATE_NOT_APPLICABLE` error on the user's function.

### Accessing declarations the plugin can't see

A template is compiled in the plugin module, so it cannot reference the user's classes directly. Two
template-only mechanisms let you access them anyway. Both are recorded *by name* and resolved when the template
is applied to user code.

**String-based access** (`boberc/Dynamic.kt`):

```kotlin
// class X { val x = 10; private val secret = "s"; var counter = 0; fun twice(n: Int) = n * 2; fun toReplace() = ... }
classNamed("test.X") {
    replaceBody("toReplace", template { self: This ->
        self.setMember("counter", self.getMember<Int>("counter") + 1)
        val point = newInstance("test.Point", self.getMember<Int>("x"), 2)    // `val` gets the precise type
        "${self.getMember<String>("secret")} ${self.callMember<Int>("twice", 1)} ${point.getMember<Int>("y")} " +
            "${objectInstance("test.Registry").getMember<String>("name")} ${callFunction<Int>("test.helper", 1)} " +
            "${getProperty<String>("test.greeting")} ${point.isInstanceOf("test.Point")}"
    })
}
```

| Intrinsic | Resolves to |
| --- | --- |
| `r.getMember<T>("p")`, `r.setMember("p", v)` | property getter / setter of `r`'s class (inherited and private members included) |
| `r.callMember<T>("f", args...)` | member function; the overload is chosen by arity and argument types |
| `callFunction<T>("pkg.f", args...)`, `getProperty<T>("pkg.p")` | top-level function / property, or a Java static (`"pkg.Class.f"`) |
| `newInstance("pkg.C", args...)`, `objectInstance("pkg.O")` | constructor call / object instance; returns `Dynamic` |
| `r.isInstanceOf("pkg.C")`, `r.castTo("pkg.C")` | `is` / `as` |
| `r.callExtension<T>("pkg.f", args...)`, `r.getExtension<T>("pkg.p")` | top-level extension function / property with receiver `r` |
| `r.callMemberExtension<T>("f", e, args...)`, `r.getMemberExtension<T>("p", e)` | member extension of `r`'s class applied to `e` (`with(r) { e.f() }`) |
| `arg("name", value)` | a named argument inside any by-name call: `box.callMember<String>("describe", arg("suffix", "?"))` |
| `r.forEachProperty { name, value -> ... }` | compile-time loop, see below |

Names must be string constants or values captured by a template lambda, so the plugin can choose them, e.g.
from `classInfo.propertyNames`. The type you expect (`getMember<Int>`) is checked against the real member.

How by-name calls are resolved:

- **Overloads** are chosen by arity and argument types. Exact class matches win over subtype matches.
- **Default arguments:** omitted parameters use their defaults, including defaults declared in an overridden
  function. `arg(...)` passes arguments by name, and a `vararg` parameter absorbs the remaining positional
  arguments.
- **Generics:** type arguments of generic functions *and* generic classes are inferred. Parameter types are
  unified with argument types; a receiver of type `Box<Int>` binds `Box`'s `T`, and a subclass is viewed as its
  supertype. The expected result type is used next, then upper bounds. So `newInstance("test.Box", 21)` is a
  `Box<Int>`, and `box.callMember<Dynamic>("map", { v: Int -> v * 2 })` infers `R = Int`.

**Looping over members.** `forEachProperty` is unrolled when the template is applied, with one copy of the block
per property of the receiver's actual class:

```kotlin
implement("toString", template { self: This ->
    buildString { self.forEachProperty { name, value -> append(name).append('=').append(value).append(';') } }
})
```

The loop covers declared and inherited properties; private ones are included when accessible from the function
being generated. `value` has each property's real type, so `if (value is Int) value + 1` works per property.
`return@forEachProperty` is rejected at plugin compile time, because there is no lambda after unrolling.

**Typed mirrors** give the same thing with compile-time typing in the template. Mirrors can be generic
(`@Mirror("test.Box") interface BoxMirror<T>`), declare generic functions (explicit type arguments are passed
through), member extensions (`fun String.tag(): String`, used as `with(self) { "x".tag() }`) and functions with
default arguments (calls that omit arguments are bound by parameter name, so names must match the real ones):

```kotlin
@Mirror interface HasX { val x: Int; fun twice(n: Int): Int }       // bound to the class the template is applied to
@Mirror("test.Point") interface PointMirror { val x: Int; val y: Int } // bound to test.Point, in every type position

replaceBody("viaMirror", template { self: HasX -> self.x * 3 + self.twice(1) })
replaceBody("pointSum", template { point: PointMirror -> point.x + point.y })
function("makePoint", template { self: HasX, y: Int -> newInstance("test.Point", self.x, y) as PointMirror })
// generated as: fun makePoint(y: Int): test.Point
```

If a member is missing, mistyped or not accessible, the error is reported as `TEMPLATE_NOT_APPLICABLE` on the
user's function or class.

### The target function, and declarations the plugin has in hand

- **`targetFunction`.** Compile-time facts about the function a template is applied to: `name`,
  `parameterCount` and `hasAnnotation(...)`. One template can then serve many functions, e.g.
  `replaceBody(template { self: This -> self.callMember<Dynamic>(targetFunction.name, ...) })` delegates each
  function to its same-name overload.
- **`FunctionRef`.** When the plugin already holds the exact function to call (it found or generated it in IR),
  pass it in instead of a name: `val define = functionRef(irFunction)`, captured by the template, then
  `define.call<R>(args)` or `receiver.callMember<R>(ref, args)`. There is no resolution by name, so this also
  reaches functions that exist only in IR.
- **Object and constructor mirrors.** `mirror<RegistryMirror>()` is the `object` a named mirror stands for,
  and `construct<PointMirror>(1, 2)` creates an instance of its class. No class names repeated as strings, no
  casts.
- **Class literals.** `p.hasAnnotation(AsNativePointer::class)`, `p.isSubtypeOf(NativeObject::class)` and
  `targetFunction.hasAnnotation(...)` accept class literals whenever the class is on the plugin's classpath.
  The class is only named, never loaded.
- **Names are constant expressions.** A name in a by-name call may be anything constant when the template is
  applied: a literal, a captured value, `targetFunction.name`, `p.name`, or a string template of those
  (`"${targetFunction.name}Impl"`).

### Working with the parameters of the function

When one template must fit functions of any arity (e.g. "delegate to an overload with an extra leading
argument"), `forEachParameter` and `mapParameters` are unrolled once per parameter of the function the template
is applied to. `p` gives `name`, `index` and `value` (with the parameter's real type), plus `hasAnnotation("…")`
and `isSubtypeOf("…")`.

Conditions that are constant when the template is applied, like `p.hasAnnotation(...)` (including `!`, `&&`,
`||` and `==` of constants), are decided right then. Only the branch that applies is built, so the other branch
may contain code that wouldn't resolve for this parameter. This is kolibri's `@NativeMethod` lowering, in full. The bytecode it
produces is byte-identical to the hand-written transformer's:

```kotlin
functionsAnnotatedWith("io.github.expo.kolibri.NativeMethod") {
    replaceBody(template { self: This ->            // implements each `external` function
        val result = temporary(self.callMember<Dynamic>(  // a compiler temporary, like `irTemporary`
            targetFunction.name, self.getMember<Long>("nativePointer"),
            mapParameters { p -> if (p.hasAnnotation(AsNativePointer::class)) nativePointerOf(p.value as NativeObject?) else p.value },
        ))
        forEachParameter { p -> if (p.hasAnnotation(AsNativePointer::class)) reachabilityFenceOf(p.value as NativeObject?) }
        result
    })
}
```

Other details that matter for this kind of template:

- `replaceBody` also applies to `external` functions, which gain a body and stop being `external`.
- Templates may use `const val`s of the plugin module; their values are inlined.
- They may reference a runtime library of the plugin directly (`nativePointerOf` above), since user code depends
  on it.
- Casts that become upcasts once real types are known (`p.value as NativeObject?` for a `Peer?`) are dropped.
- `@JvmField` properties are accessed as fields.
- `This` also works in static functions of a class.
- `callFunction("pkg.Class.fn", ...)` also finds static functions that exist only in IR (added by an IR
  extension), which the frontend-based lookup can't see.
- Casts that are upcasts are dropped using the compiler's full subtype check, so generic types count too
  (`Class<Player>` to `Class<out SharedObject>`).
- `val x = e; return x` with nothing in between becomes `return e`.

### Using templates from your own IR code

When your plugin already has its own IR transformers, apply a template to any function directly:

```kotlin
IrTemplates.replaceBody(function, template { -> ... }, pluginContext)   // or generateBody
```

It returns `false`, after reporting `TEMPLATE_NOT_APPLICABLE` on the function (or, for a function without
source, its class), when the template doesn't fit. It never crashes the compiler. expo-modules-v2's shared-object
registration does this:

```kotlin
private fun registration(jsName: String, define: FunctionRef, reportsMemoryPressure: Boolean) = template { ->
    mirror<SharedObjectRegistryMirror>().reserve(This::class.java)
    val builder = construct<ModuleBuilderMirror>()
    define.call<String?>(builder)
    mirror<SharedObjectRegistryMirror>().register(jsName, This::class.java, builder, reportsMemoryPressure)
}

IrTemplates.replaceBody(register, registration(jsName, functionRef(define), reportsMemoryPressure), context)
```

### Splicing IR the plugin computed (quasi-quotes)

Real plugins compute things: a type descriptor per parameter, a list of arguments, a field they added. A
template receives such IR through **handles** it captures. Inside the template, a handle is used like a
value:

| Handle (create with `boberc.ir.*`) | Use in a template |
| --- | --- |
| `exprRef(expression)` / `exprRef(parameterOrVariable)` | `ref.get<T>()` (a fresh copy per use), `ref.set(v)` |
| `exprListRef(expressions)` | `*refs.spread<T>()` as arguments (also into a vararg), `refs.run()` as statements |
| `typeRef(irType)` | `type.javaClass()` (`T::class.java`), `value.coerceTo(type)`, `typeArgs(type)` for by-name calls |
| `fieldRef(irField)` | `field.get<T>()` (static), `self.getField<T>(field)`, `self.setField(field, v)` |
| `functionRef(fn)` / `constructorRef(ctor)` | `ref.call<T>(...)`, `self.callMember<T>(ref, ...)` |

Templates compose this way: the plugin's own logic decides *what* to build, and each piece is a template. Apply
the pieces with `IrQuotes(pluginContext, scope)`. `scope` is the function, constructor, class or file the code
lives in:

```kotlin
private fun function(builder: ExprRef, jsName: String, argTypes: ExprListRef, returns: ExprRef, isAsync: Boolean) =
    template { ->
        if (isAsync) builder.get<ModuleBuilderMirror>().function(jsName, *argTypes.spread<AnyTypeMirror>(), returns = returns.get(), isAsync = true)
        else builder.get<ModuleBuilderMirror>().function(jsName, *argTypes.spread<AnyTypeMirror>(), returns = returns.get())
    }

val quotes = IrQuotes(pluginContext, define)
val statements = exports.flatMap { quotes.statements(function(builder, it.jsName, exprListRef(argTypesOf(it)), ...)) }
IrTemplates.setBody(define, statements + quotes.returning(template { -> jsName }), pluginContext)
```

- `IrQuotes.expression(t)`, `statements(t)`, `returning(t)` and `variable(name, type, initializer = t)`.
  The variable is a local whose `exprRef` later templates use.
- `IrTemplates.setBody(fn, statements)`, `IrTemplates.constructorBody(ctor, template?)` (`super()`, the instance
  initializers, then the template) and `generateBody` / `replaceBody`.
- `IrDeclarations` adds what a plugin declares only in IR:
  - `addFunction(owner, name, returnType, parameters, ...)` and `addField(owner, name, type, initializer = template)`;
  - `setInitializer(field, template)`, which keeps the field's existing body and therefore its line numbers, and
    `initializeProperty(property, template)`, which also gives the property its default getter;
  - `addAnnotation(declaration, template { -> construct<AnnotationMirror>() })`;
  - `realizeFakeOverride(fn, origin)`, which turns an inherited member into a real one you can fill;
  - `defaultGetter(property)`.
- `val x = temporary(value)` makes `x` a compiler temporary (`IR_TEMPORARY_VARIABLE`, named `tmp_0`, ...), like
  `irTemporary` in hand-written IR. It is kept out of the debugger's local variable table, so the bytecode
  matches.
- Captured constants prune `if`/`when` at materialization: only the branch that applies is built. Template
  lambdas may be `suspend` (e.g. a block passed to a coroutine launcher), and a `when` whose branches throw is
  typed by its other branches.

### Generating declarations from shapes (FIR)

A `@Shape` class or interface is the plain-Kotlin description of declarations to generate in FIR. The plugin
still decides *where* things go, using `FirDeclarationGenerationExtension` callbacks and predicates. The
shape says *what* they look like:

```kotlin
@Mirror("com.example.Codec") interface CodecMirror<T> { val name: String; fun decode(reader: ReaderMirror): T }

@Shape private interface MarkerShape : MarkerMirror                 // supertypes to add
@Shape private abstract class CodecShape<R> : CodecMirror<R> {       // a nested class; R is bound per class
    abstract override val name: String
    abstract override fun decode(reader: ReaderMirror): R
}
@Shape private abstract class CompanionShape<R> private constructor() { abstract val codec: CodecMirror<R> }

private val codecShape = shape(CodecShape::class)

override fun getCallableNamesForClass(classSymbol, context) = codecShape.callableNames      // <init>, name, decode
override fun generateNestedClassLikeDeclaration(owner, name, context) =
    createNestedClassFromShape(owner, name, codecShape.bind(owner.defaultType()), Key).symbol
override fun generateFunctions(callableId, context) =
    createFunctionsFromShape(context!!.owner, codecShape.bind(recordType), callableId.callableName, Key).map { it.symbol }
```

- **Derived signatures.** A `vararg name: ParameterSplice` parameter marks where parameters the plugin computes
  go. Pass them as `ShapeParameter`s to `createFunctionFromShape(owner, shape, member, key, name, parameters)`,
  which can also rename the member, or to `createConstructorsFromShape`. kolibri declares the native overload of
  each user function this way:
  ```kotlin
  @Shape private abstract class NativeOverloadShape<R> {
      private external fun nativeOverload(pointer: Long, vararg parameters: ParameterSplice): R
  }
  createFunctionFromShape(owner, shape.bind(original.resolvedReturnType), "nativeOverload", Key,
      name = original.name, parameters = original.valueParameterSymbols.map { ShapeParameter(it.name, typeOf(it)) })
  ```
- `ClassShape.bind(vararg coneTypes)` substitutes the shape's type parameters. `This` is the generated class,
  and target types are written as mirrors.
- `createNestedClassFromShape`, `createTopLevelClassFromShape` and `createCompanionObjectFromShape` take the
  kind, visibility, `open` and supertypes from the shape. Each has a `config` block that runs last.
- `createConstructorsFromShape`, `createPropertiesFromShape` and `createFunctionsFromShape` cover the members.
  `supertypesOf(shape)` is for `FirSupertypeGenerationExtension`. `callableNames` and `memberNameSet` list
  what to generate.
- The shape's visibility is the visibility of the generated declarations. Member bodies are ignored, because IR
  fills them: in a class shape, `abstract` members are generated final; in an interface shape they stay
  abstract.
- Each function calls the matching `org.jetbrains.kotlin.fir.plugin` builder, so the declaration is exactly the
  one a hand-written generator would build. expo's three FIR generators were replaced with shapes and the
  generated bytecode did not change.

See `Described.kt` in the example plugin for a complete feature built from shapes plus templates: an added
supertype, a private nested class, a generated or user-written companion, and their bodies.

### Selecting and querying classes

```kotlin
classesAnnotatedWith("my.Annotation") { ... }
classNamed("com.example.X") { ... }                                   // by fully qualified name
classesMatching({ it.hasProperty("id") && it.hasFunction("describe") }) {
    replaceBody("describe") {
        val info = classInfo!!                                         // the owner class
        val name = info.simpleName
        val first = info.propertyNames.first()
        template { self: This -> "$name: $first=${self.getMember<Any?>(first)}" }
    }
}
functionsAnnotatedWith("my.Stub") { ... }
functionsNamed("com.example.compute") { ... }
```

`ClassInfo` gives `fqName`, `classId`, `simpleName`, `packageName`, `isObject`, `isInterface`,
`propertyNames`, `functionNames`, `hasProperty()` and `hasFunction()` (declared members, by name). Both
contexts can look up any class of the compilation by name: `MemberContext.findClass("…")` while generating,
`BodyContext.findClass("…")` while replacing bodies.

### Declaring a plugin (`boberc.plugin`)

```kotlin
val plugin = bobercPlugin {
    classesAnnotatedWith("my.Annotation") {
        // New members.
        function(template(This::greet), name = "greet") { mapOf("className" to className) } // splices per class
        function(template("replicate"))                                                      // generic template
        function("shout") { val n = className; template { self: This, s: String -> "$n: $s" } }

        // Implement / override an inherited member (abstract or open). `operator`/`infix` are inherited.
        implement("toString") { val n = className; template { self: This -> "$n#${self.hashCode()}" } }
        implement("compareTo", template { self: This, other: This -> self.hashCode().compareTo(other.hashCode()) })

        // Redefine an existing, user-written member: its body is replaced.
        replaceBody("compute", template { x: Int -> x * 2 })
    }

    // Redefine user functions annotated with @my.Stub; choose the template per function (null = keep it).
    functionsAnnotatedWith("my.Stub") {
        replaceBody {
            when {
                functionName.startsWith("add") -> template { a: Int, b: Int -> a + b }
                function.returnType.isString() -> { val n = functionName; template { -> "stubbed $n" } }
                else -> null
            }
        }
    }

    topLevelFunction("my.generated", template(::fizzBuzz))
    topLevelFunction("my.generated", template(String::shout), splices = mapOf("suffix" to "!!!"))
}

class MyRegistrar : CompilerPluginRegistrar() {
    override val pluginId get() = "my.plugin"
    override val supportsK2 get() = true
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        plugin.registerExtensions(this) // registers the FIR generator and the IR body generator
    }
}
```

### Lower-level building blocks

If you already have your own FIR extensions, use the pieces directly:

- `FunctionTemplate.instantiate(name, splices)` returns a `TemplateInstance`, with splice values validated eagerly.
- `FirExtension.createMemberFunctionFromTemplate(owner, instance, isOverride)` and
  `FirExtension.createTopLevelFunctionFromTemplate(packageFqName, instance)` build the FIR signatures.
- Register `BobercIrGenerationExtension` to fill the bodies, or call `IrTemplates.generateBody(function, instance, context)`.
- `IrTemplates.replaceBody(function, instance, context)` redefines any IR function you select yourself.
- `FunctionTemplate.render()` gives a readable dump of the model; `encode()` / `FunctionTemplate.decode()` serialize it.
  `ClassShape` has the same `render()`, `encode()` and `decode()`.

## Supported in template bodies

Constants, locals (`val`/`var`), calls (functions, property accessors, operators, inline stdlib functions,
Java members), constructor calls, lambdas (including lambdas with receivers), `return`, `if`/`when`,
`while`/`do-while`/`for`, `break`/`continue`, `try`/`catch`/`finally`, `throw`, string templates, casts and
`is` checks, varargs and spread, objects, enum entries, Java static fields, `X::class`, and default
parameter values.

Not supported yet (reported as `UNSUPPORTED_IN_TEMPLATE`): callable and property references (`String::length`),
local functions and classes, anonymous objects, `super` calls, calls to other templates, context parameters,
and suspend or inline templates.

## Known limitations of the POC

- The `bobercPlugin {}` DSL generates, implements and redefines **functions**. Classes, supertypes,
  constructors and properties are generated from shapes in your own FIR extension (see above); the DSL has no
  shortcut for them yet.
- Shapes cannot declare members with their own type parameters, default values, nested classes, or `This` in a
  supertype. Plugin-computed parameters (`ParameterSplice`) are given as FIR types: there is no type-level `if`.
- A redefined function cannot call its original body yet (useful for wrappers like logging or memoization).
- Template lambdas cannot have receivers (`template { this: ... }` isn't expressible); use a `self: This`
  parameter. They cannot be generic. Binding checks are class-level (type arguments aren't compared).
- By-name resolution is deliberately simpler than Kotlin's:
  - Type checks are class-level (type arguments of argument types aren't compared).
  - Inference binds each type parameter to the first argument that mentions it; there is no common supertype
    computation.
  - There are no spread arguments (`*array`), SAM conversions, or context parameters.
- The compile-time loops are `forEachProperty` and `forEachParameter`/`mapParameters`; there is no loop over a
  class's functions.
- `ClassInfo` lists members by name only, because types are not resolved yet when FIR generation asks.
- `implement` builds the override's signature from the template, so it must match the inherited member
  (with `This` for the class itself). A missing overridden member is detected in IR.
- Splices of expressions, types and fields come from the plugin's IR (quasi-quotes). There is still no
  compile-time loop over a class's functions.
- Extension-function *types* in template signatures (`block: StringBuilder.() -> Unit`) lose their
  `@ExtensionFunctionType` attribute. Inside bodies they work.
- Failures during materialization (e.g. a referenced library missing from the target classpath) currently
  throw `BobercTemplateException` instead of reporting a diagnostic.
- Generated code has no source positions (`UNDEFINED_OFFSET`).
- A published plugin has to shade `boberc-runtime` into its jar.

## Modules

| Module | What it is |
| --- | --- |
| [`:boberc-runtime`](boberc-runtime/src/boberc) | What plugin authors depend on: the template API, the template model and codec, the FIR/IR materializers and the `bobercPlugin {}` DSL. |
| [`:compiler-plugin`](compiler-plugin/src/boberc/compiler) | The boberc compiler plugin: captures `@Template` functions and `@Shape` classes and lowers the `template()` / `shape()` intrinsics. |
| [`:example-plugin`](example-plugin/src/boberc/example) | A compiler plugin written *with* boberc. Gradle applies boberc to its compilation via `kotlinCompilerPluginClasspathMain`. |
| [`:gradle-plugin`](gradle-plugin/src/boberc/gradle) | Gradle plugin that applies boberc and adds `boberc-runtime` to a plugin author's project. |

## Tests

```
./gradlew build
```

- `boberc-runtime/test`: codec round trips, escaping, splice validation, format versioning.
- `compiler-plugin/testData`: capture tests (golden model rendering, many constructs with an encode/decode
  round trip, template lambdas with captured values, `temporary()`, shapes including `external` and
  `ParameterSplice`) and diagnostics for misuse. These run on the [Kotlin compiler test framework][test-framework].
- `example-plugin/testData`: **end-to-end**. User code is compiled with the example plugin and executed
  (`box`). Generated signatures are type-checked in diagnostic tests. `lambdas.kt` covers implementing `toString`
  and the abstract `Comparable.compareTo` (including `a < b`), redefining members and annotated functions,
  and lambda-generated members. `dynamicAccess.kt` covers mirrors, string-based access, and class selection by
  name and by structure. `advancedAccess.kt` covers generic classes and members, extension members, default and
  named arguments, and `forEachProperty`. `functionRefs.kt` covers `FunctionRef`. `shapes.kt` covers a feature
  generated from shapes in FIR and filled by templates in IR. The `.fir.txt` and `.ir.txt` dumps show
  exactly what was generated.

To add a test, drop a `.kt` file into a `testData/box` or `testData/diagnostics` directory; the JUnit classes
are regenerated on the next test run. `-PupdateTestData` rewrites the expected dumps from the actual output.

[test-framework]: https://github.com/JetBrains/kotlin/blob/master/compiler/test-infrastructure/ReadMe.md
[expo]: ../expo-modules-v2-android/compiler-plugin/boberc/README.md
[kolibri]: ../kolibri/compiler-plugin/boberc/README.md
