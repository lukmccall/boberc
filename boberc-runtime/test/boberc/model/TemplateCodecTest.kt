package boberc.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateCodecTest {
    private val string = TType.Class("kotlin/String", emptyList(), isNullable = false)
    private val int = TType.Class("kotlin/Int", emptyList(), isNullable = false)

    private fun template(body: List<TExpr>) = FunctionTemplate(
        name = "t",
        visibility = TVisibility.PUBLIC,
        isOperator = false,
        isInfix = false,
        typeParameters = listOf(TTypeParameter("T", TVariance.OUT, isReified = false, bounds = listOf(TType.This(true)))),
        receiverType = TType.This(isNullable = false),
        parameters = listOf(
            TParameter("label", string, isSplice = true, null, false, false, null),
            TParameter("count", int, isSplice = false, null, false, false, TExpr.Const(TConstKind.INT, "3", int)),
        ),
        returnType = TType.Class(
            "kotlin/collections/Map",
            listOf(TTypeArgument.Star, TTypeArgument.Projection(TVariance.IN, TType.TypeParameter(0, true))),
            isNullable = true,
        ),
        body = TExpr.Block(body, TType.Class("kotlin/Unit", emptyList(), false), origin = null, isComposite = false),
    )

    @Test
    fun `round trip preserves the model, including tricky strings`() {
        val tricky = "quote \" backslash \\ newline \n tab \t unicode ✓ \u0001 (parens) _"
        val original = template(
            listOf(
                TExpr.Variable(1, "<iterator>", string, isVar = true, origin = "FOR_LOOP_ITERATOR",
                    initializer = TExpr.Const(TConstKind.STRING, tricky, string)),
                TExpr.Return(0, TExpr.Const(TConstKind.NULL, null, string)),
            )
        )
        val decoded = FunctionTemplate.decode(original.encode())
        assertEquals(original.render(), decoded.render())
        val variable = decoded.body!!.statements[0] as TExpr.Variable
        assertEquals(tricky, (variable.initializer as TExpr.Const).value)
        assertTrue(original.encode().all { it.code in 0x20..0x7e }, "encoded form must be printable ASCII")
    }

    @Test
    fun `instantiate validates splice values`() {
        val t = template(emptyList())
        assertEquals("Foo", t.instantiate(splices = mapOf("label" to "Foo")).splices["label"])
        assertFailsWith<IllegalArgumentException> { t.instantiate() }
        assertFailsWith<IllegalArgumentException> { t.instantiate(splices = mapOf("label" to 1)) }
        assertFailsWith<IllegalArgumentException> { t.instantiate(splices = mapOf("label" to null)) }
        assertFailsWith<IllegalArgumentException> { t.instantiate(splices = mapOf("label" to "x", "other" to 1)) }
    }

    @Test
    fun `incompatible format versions are rejected`() {
        val encoded = template(emptyList()).encode().replaceFirst("(template ${TemplateCodec.FORMAT_VERSION} ", "(template 999 ")
        val error = assertFailsWith<IllegalArgumentException> { FunctionTemplate.decode(encoded) }
        assertTrue("v999" in error.message!!)
    }

    @Test
    fun `shapes round trip and reject other format versions`() {
        val r = TType.TypeParameter(0, isNullable = false)
        val shape = ClassShape(
            name = "CodecShape",
            kind = TClassKind.CLASS,
            visibility = TVisibility.PRIVATE,
            isOpen = false,
            typeParameters = listOf(TTypeParameter("R", TVariance.INVARIANT, isReified = false, bounds = emptyList())),
            supertypes = listOf(TType.Named("com.example.Codec", listOf(TTypeArgument.Projection(TVariance.INVARIANT, r)), false)),
            constructors = listOf(ConstructorShape(TVisibility.PRIVATE, isPrimary = true, emptyList())),
            members = listOf(
                MemberShape.Property("name \"quoted\"", TVisibility.PUBLIC, TModality.ABSTRACT, isOverride = true, string, isVar = true),
                MemberShape.Function(
                    "decode", TVisibility.PUBLIC, TModality.OPEN, isOverride = false, isOperator = false, isInfix = true, isExternal = true,
                    receiverType = TType.This(isNullable = true),
                    parameters = listOf(TParameter("values", string, isSplice = false, varargElementType = string, false, false, null)),
                    returnType = r,
                ),
            ),
        )
        val decoded = ClassShape.decode(shape.encode())
        assertEquals(shape.render(), decoded.render())
        assertEquals(listOf("name \"quoted\"", "decode"), decoded.memberNames)

        val encoded = shape.encode().replaceFirst("(shape ${TemplateCodec.FORMAT_VERSION} ", "(shape 999 ")
        val error = assertFailsWith<IllegalArgumentException> { ClassShape.decode(encoded) }
        assertTrue("v999" in error.message!!)
    }
}
