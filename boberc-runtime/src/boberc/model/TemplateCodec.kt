package boberc.model

import boberc.model.SNode.Atom
import boberc.model.SNode.SList

/** Converts [FunctionTemplate] models to and from their S-expression form. */
internal object TemplateCodec {
    /** Bumped on incompatible format changes; a plugin compiled with another version fails fast. */
    const val FORMAT_VERSION = 8

    fun encode(template: FunctionTemplate): String = SExpr.compact(encodeToSExpr(template))

    fun decode(encoded: String): FunctionTemplate = Decoder.template(SExpr.parse(encoded))

    fun encodeToSExpr(template: FunctionTemplate): SNode = Encoder.template(template)

    fun encode(shape: ClassShape): String = SExpr.compact(Encoder.shape(shape))

    fun encodeToSExpr(shape: ClassShape): SNode = Encoder.shape(shape)

    fun decodeShape(encoded: String): ClassShape = Decoder.shape(SExpr.parse(encoded))

    // ------------------------------------------------------------------------------------------------ encoding

    private object Encoder {
        fun sym(s: String) = Atom(s, quoted = false)
        fun str(s: String) = Atom(s, quoted = true)
        fun bool(b: Boolean) = sym(b.toString())
        fun int(i: Int) = sym(i.toString())
        val NONE = sym("_")
        fun optStr(s: String?) = s?.let(::str) ?: NONE
        fun list(vararg items: SNode) = SList(items.toList())
        fun list(head: String, vararg items: SNode) = SList(listOf(sym(head)) + items)
        fun <T> many(items: List<T>, f: (T) -> SNode) = SList(items.map(f))

        fun template(t: FunctionTemplate) = list(
            "template", int(FORMAT_VERSION), str(t.name), bool(t.isLambda), sym(t.visibility.name), bool(t.isOperator), bool(t.isInfix),
            many(t.typeParameters, ::typeParameter),
            t.receiverType?.let(::type) ?: NONE,
            many(t.parameters, ::parameter),
            type(t.returnType),
            t.body?.let(::expr) ?: NONE,
        )

        fun shape(s: ClassShape) = list(
            "shape", int(FORMAT_VERSION), str(s.name), sym(s.kind.name), sym(s.visibility.name), bool(s.isOpen),
            many(s.typeParameters, ::typeParameter), many(s.supertypes, ::type),
            many(s.constructors) { list("ctor", sym(it.visibility.name), bool(it.isPrimary), many(it.parameters, ::parameter)) },
            many(s.members, ::member),
        )

        fun member(m: MemberShape): SNode = when (m) {
            is MemberShape.Property -> list(
                "property", str(m.name), sym(m.visibility.name), sym(m.modality.name), bool(m.isOverride), type(m.type), bool(m.isVar),
            )
            is MemberShape.Function -> list(
                "function", str(m.name), sym(m.visibility.name), sym(m.modality.name), bool(m.isOverride), bool(m.isOperator),
                bool(m.isInfix), bool(m.isExternal), m.receiverType?.let(::type) ?: NONE, many(m.parameters, ::parameter), type(m.returnType),
            )
        }

        fun typeParameter(p: TTypeParameter) =
            list("typeParam", str(p.name), sym(p.variance.name), bool(p.isReified), many(p.bounds, ::type))

        fun parameter(p: TParameter) = list(
            "param", str(p.name), type(p.type), bool(p.isSplice),
            p.varargElementType?.let(::type) ?: NONE,
            bool(p.isCrossinline), bool(p.isNoinline),
            p.defaultValue?.let(::expr) ?: NONE,
        )

        fun type(t: TType): SNode {
            val q = if (t.isNullable) "?" else ""
            return when (t) {
                is TType.Class -> SList(listOf(sym("class$q"), str(t.classId)) + t.arguments.map(::typeArgument))
                is TType.TypeParameter -> list("typeParam$q", int(t.index))
                is TType.This -> list("this$q")
                is TType.Named -> SList(listOf(sym("named$q"), str(t.fqName)) + t.arguments.map(::typeArgument))
                is TType.Dynamic -> list("dynamic$q")
            }
        }

        fun typeArgument(a: TTypeArgument): SNode = when (a) {
            TTypeArgument.Star -> sym("*")
            is TTypeArgument.Projection -> list(a.variance.name.lowercase(), type(a.type))
        }

        fun ref(r: TValueRef): SNode = when (r) {
            TValueRef.Receiver -> sym("receiver")
            is TValueRef.Parameter -> list("param", int(r.index))
            is TValueRef.Local -> list("local", int(r.id))
        }

        fun callee(c: TCallee): SNode = when (c) {
            is TCallee.Callable -> list("callable", str(c.packageFqName), optStr(c.className), str(c.name), sym(c.accessor.name), str(c.signature))
            is TCallee.Builtin -> list("builtin", str(c.name), str(c.signature))
        }

        fun optExpr(e: TExpr?) = e?.let(::expr) ?: NONE

        fun expr(e: TExpr): SNode = when (e) {
            is TExpr.Const -> list("const", sym(e.kind.name), optStr(e.value), type(e.type))
            is TExpr.GetValue -> list("get", ref(e.ref))
            is TExpr.SetValue -> list("set", ref(e.ref), expr(e.value), optStr(e.origin))
            is TExpr.Variable -> variable(e)
            is TExpr.Call -> list(
                "call", callee(e.callee), many(e.typeArguments, ::type), many(e.arguments, ::optExpr), type(e.type), optStr(e.origin),
            )
            is TExpr.ConstructorCall -> list(
                "new", str(e.classId), str(e.signature), many(e.typeArguments, ::type), int(e.constructorTypeArgumentsCount),
                many(e.arguments, ::optExpr), type(e.type), optStr(e.origin),
            )
            is TExpr.GetObject -> list("object", str(e.classId), type(e.type))
            is TExpr.GetEnumEntry -> list("enumEntry", str(e.classId), str(e.entryName), type(e.type))
            is TExpr.GetField -> list("field", str(e.classId), str(e.fieldName), optExpr(e.receiver), type(e.type))
            is TExpr.ClassReference -> list("classRef", type(e.classType), type(e.type))
            is TExpr.GetClass -> list("getClass", expr(e.argument), type(e.type))
            is TExpr.Return -> list("return", int(e.targetId), expr(e.value))
            is TExpr.Block -> list(if (e.isComposite) "composite" else "block", many(e.statements, ::expr), type(e.type), optStr(e.origin))
            is TExpr.When -> list(
                "when",
                many(e.branches) { list(if (it.isElse) "else" else "branch", expr(it.condition), expr(it.result)) },
                type(e.type), optStr(e.origin),
            )
            is TExpr.StringConcat -> list("concat", many(e.arguments, ::expr), type(e.type))
            is TExpr.TypeOperator -> list("typeOp", sym(e.operator), type(e.typeOperand), expr(e.argument), type(e.type))
            is TExpr.Loop -> list(
                if (e.isDoWhile) "doWhile" else "while", int(e.id), optStr(e.label), expr(e.condition), optExpr(e.body), optStr(e.origin),
            )
            is TExpr.Break -> list("break", int(e.loopId))
            is TExpr.Continue -> list("continue", int(e.loopId))
            is TExpr.Throw -> list("throw", expr(e.value))
            is TExpr.Try -> list(
                "try", expr(e.tryResult), many(e.catches) { list("catch", variable(it.parameter), expr(it.result)) },
                optExpr(e.finallyExpression), type(e.type),
            )
            is TExpr.Vararg -> list("vararg", many(e.elements, ::expr), type(e.elementType), type(e.type))
            is TExpr.Spread -> list("spread", expr(e.expression))
            is TExpr.Dynamic -> list(
                "dynamic", sym(e.op.name), expr(e.name), optExpr(e.receiver), optExpr(e.extensionReceiver),
                many(e.typeArguments, ::type), many(e.arguments, ::expr), type(e.type),
            )
            is TExpr.NamedArgument -> list("namedArg", expr(e.name), expr(e.value))
            is TExpr.TypeArguments -> list("typeArgs", many(e.types, ::expr))
            is TExpr.ForEachParameter -> list("forEachParameter", int(e.parameterLocal), many(e.body, ::expr))
            is TExpr.MapParameters -> list("mapParameters", int(e.parameterLocal), many(e.statements, ::expr), expr(e.result))
            is TExpr.TargetInfo -> list("targetInfo", sym(e.op.name), optExpr(e.argument))
            is TExpr.ParameterInfo -> list("parameterInfo", int(e.parameterLocal), sym(e.op.name), optExpr(e.argument))
            is TExpr.ForEachProperty -> list(
                "forEachProperty", expr(e.receiver), int(e.nameLocal), str(e.nameVariable), int(e.valueLocal),
                str(e.valueVariable), many(e.body, ::expr),
            )
            is TExpr.Lambda -> list(
                "lambda", int(e.id),
                many(e.parameters) { list("lambdaParam", int(it.id), sym(it.kind.name), str(it.name), type(it.type)) },
                type(e.returnType), many(e.body, ::expr), type(e.type), optStr(e.origin), bool(e.isSuspend),
            )
        }

        fun variable(v: TExpr.Variable) =
            list("var", int(v.id), str(v.name), type(v.type), bool(v.isVar), optStr(v.origin), optExpr(v.initializer))
    }

    // ------------------------------------------------------------------------------------------------ decoding

    private object Decoder {
        /** Positional reader over the items of a list node, after its head symbol. */
        class R(val node: SList) {
            private var i = 1
            val head: String = (node.items.firstOrNull() as? Atom)?.text ?: error("Malformed template data: $node")

            fun next(): SNode = node.items.getOrNull(i++) ?: error("Malformed template data: missing field in ($head ...)")
            fun atom(): String = (next() as? Atom)?.text ?: error("Malformed template data: expected atom in ($head ...)")
            fun int(): Int = atom().toInt()
            fun bool(): Boolean = atom().toBooleanStrict()
            fun optStr(): String? = next().let { if (isNone(it)) null else (it as? Atom)?.text ?: error("Malformed template data: expected atom in ($head ...)") }
            fun list(): SList = next() as? SList ?: error("Malformed template data: expected list in ($head ...)")
            fun isNone(n: SNode) = n is Atom && !n.quoted && n.text == "_"
            fun <T> opt(f: (SNode) -> T): T? = next().let { if (isNone(it)) null else f(it) }
            fun <T> many(f: (SNode) -> T): List<T> = list().items.map(f)
            fun <T> rest(f: (SNode) -> T): List<T> = node.items.drop(i).map(f).also { i = node.items.size }
        }

        fun r(n: SNode): R = R(n as? SList ?: error("Malformed template data: expected list, got $n"))

        /** Reads the format version and fails fast when [what] was encoded by another boberc version. */
        fun R.checkVersion(what: String) {
            val version = int()
            require(version == FORMAT_VERSION) {
                "$what was encoded with boberc format v$version, but this runtime supports v$FORMAT_VERSION. " +
                        "Recompile the plugin with a matching boberc version."
            }
        }

        fun template(n: SNode): FunctionTemplate = with(r(n)) {
            check(head == "template")
            checkVersion("Template")
            FunctionTemplate(
                name = atom(),
                isLambda = bool(),
                visibility = TVisibility.valueOf(atom()),
                isOperator = bool(),
                isInfix = bool(),
                typeParameters = many(::typeParameter),
                receiverType = opt(::type),
                parameters = many(::parameter),
                returnType = type(next()),
                body = opt { expr(it) as TExpr.Block },
            )
        }

        fun shape(n: SNode): ClassShape = with(r(n)) {
            check(head == "shape")
            checkVersion("Shape")
            ClassShape(
                name = atom(),
                kind = TClassKind.valueOf(atom()),
                visibility = TVisibility.valueOf(atom()),
                isOpen = bool(),
                typeParameters = many(::typeParameter),
                supertypes = many(::type),
                constructors = many { c -> with(r(c)) { ConstructorShape(TVisibility.valueOf(atom()), bool(), many(::parameter)) } },
                members = many(::member),
            )
        }

        fun member(n: SNode): MemberShape = with(r(n)) {
            when (head) {
                "property" -> MemberShape.Property(
                    name = atom(), visibility = TVisibility.valueOf(atom()), modality = TModality.valueOf(atom()),
                    isOverride = bool(), type = type(next()), isVar = bool(),
                )
                "function" -> MemberShape.Function(
                    name = atom(), visibility = TVisibility.valueOf(atom()), modality = TModality.valueOf(atom()),
                    isOverride = bool(), isOperator = bool(), isInfix = bool(), isExternal = bool(), receiverType = opt(::type),
                    parameters = many(::parameter), returnType = type(next()),
                )
                else -> error("Unknown shape member: $head")
            }
        }

        fun typeParameter(n: SNode) = with(r(n)) {
            TTypeParameter(atom(), TVariance.valueOf(atom()), bool(), many(::type))
        }

        fun parameter(n: SNode) = with(r(n)) {
            TParameter(
                name = atom(), type = type(next()), isSplice = bool(), varargElementType = opt(::type),
                isCrossinline = bool(), isNoinline = bool(), defaultValue = opt(::expr),
            )
        }

        fun type(n: SNode): TType = with(r(n)) {
            val nullable = head.endsWith("?")
            when (head.removeSuffix("?")) {
                "class" -> TType.Class(atom(), rest(::typeArgument), nullable)
                "typeParam" -> TType.TypeParameter(int(), nullable)
                "this" -> TType.This(nullable)
                "named" -> TType.Named(atom(), rest(::typeArgument), nullable)
                "dynamic" -> TType.Dynamic(nullable)
                else -> error("Unknown type node: $head")
            }
        }

        fun typeArgument(n: SNode): TTypeArgument {
            if (n is Atom && n.text == "*") return TTypeArgument.Star
            return with(r(n)) { TTypeArgument.Projection(TVariance.valueOf(head.uppercase()), type(next())) }
        }

        fun ref(n: SNode): TValueRef {
            if (n is Atom && n.text == "receiver") return TValueRef.Receiver
            return with(r(n)) {
                when (head) {
                    "param" -> TValueRef.Parameter(int())
                    "local" -> TValueRef.Local(int())
                    else -> error("Unknown value reference: $head")
                }
            }
        }

        fun callee(n: SNode): TCallee = with(r(n)) {
            when (head) {
                "callable" -> TCallee.Callable(atom(), optStr(), atom(), TAccessor.valueOf(atom()), atom())
                "builtin" -> TCallee.Builtin(atom(), atom())
                else -> error("Unknown callee: $head")
            }
        }

        fun optExpr(n: SNode): TExpr? = if (n is Atom && !n.quoted && n.text == "_") null else expr(n)

        fun expr(n: SNode): TExpr = with(r(n)) {
            when (head) {
                "const" -> TExpr.Const(TConstKind.valueOf(atom()), optStr(), type(next()))
                "get" -> TExpr.GetValue(ref(next()))
                "set" -> TExpr.SetValue(ref(next()), expr(next()), optStr())
                "var" -> variable(this)
                "call" -> TExpr.Call(callee(next()), many(::type), many(::optExpr), type(next()), optStr())
                "new" -> TExpr.ConstructorCall(atom(), atom(), many(::type), int(), many(::optExpr), type(next()), optStr())
                "object" -> TExpr.GetObject(atom(), type(next()))
                "enumEntry" -> TExpr.GetEnumEntry(atom(), atom(), type(next()))
                "field" -> TExpr.GetField(atom(), atom(), opt(::expr), type(next()))
                "classRef" -> TExpr.ClassReference(type(next()), type(next()))
                "getClass" -> TExpr.GetClass(expr(next()), type(next()))
                "return" -> TExpr.Return(int(), expr(next()))
                "block", "composite" -> TExpr.Block(many(::expr), type(next()), optStr(), isComposite = head == "composite")
                "when" -> TExpr.When(
                    many { b -> with(r(b)) { TExpr.Branch(expr(next()), expr(next()), isElse = head == "else") } },
                    type(next()), optStr(),
                )
                "concat" -> TExpr.StringConcat(many(::expr), type(next()))
                "typeOp" -> TExpr.TypeOperator(atom(), type(next()), expr(next()), type(next()))
                "while", "doWhile" -> TExpr.Loop(int(), head == "doWhile", optStr(), expr(next()), opt(::expr), optStr())
                "break" -> TExpr.Break(int())
                "continue" -> TExpr.Continue(int())
                "throw" -> TExpr.Throw(expr(next()))
                "try" -> TExpr.Try(
                    expr(next()),
                    many { c -> with(r(c)) { TExpr.Catch(variable(r(next())), expr(next())) } },
                    opt(::expr), type(next()),
                )
                "vararg" -> TExpr.Vararg(many(::expr), type(next()), type(next()))
                "spread" -> TExpr.Spread(expr(next()))
                "dynamic" -> TExpr.Dynamic(
                    TDynamicOp.valueOf(atom()), expr(next()), opt(::expr), opt(::expr), many(::type), many(::expr), type(next()),
                )
                "namedArg" -> TExpr.NamedArgument(expr(next()), expr(next()))
                "typeArgs" -> TExpr.TypeArguments(many(::expr))
                "forEachParameter" -> TExpr.ForEachParameter(int(), many(::expr))
                "mapParameters" -> TExpr.MapParameters(int(), many(::expr), expr(next()))
                "targetInfo" -> TExpr.TargetInfo(TTargetInfo.valueOf(atom()), opt(::expr))
                "parameterInfo" -> TExpr.ParameterInfo(int(), TParameterInfo.valueOf(atom()), opt(::expr))
                "forEachProperty" -> TExpr.ForEachProperty(expr(next()), int(), atom(), int(), atom(), many(::expr))
                "lambda" -> TExpr.Lambda(
                    int(),
                    many { p ->
                        with(r(p)) { TExpr.LambdaParameter(int(), TExpr.LambdaParameterKind.valueOf(atom()), atom(), type(next())) }
                    },
                    type(next()), many(::expr), type(next()), optStr(), bool(),
                )
                else -> error("Unknown expression node: $head")
            }
        }

        fun variable(r: R): TExpr.Variable = with(r) {
            check(head == "var") { "Expected variable, got $head" }
            TExpr.Variable(int(), atom(), type(next()), bool(), optStr(), opt(::expr))
        }
    }
}
