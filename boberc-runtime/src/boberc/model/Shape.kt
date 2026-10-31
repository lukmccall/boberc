package boberc.model

/**
 * The captured **shape** of a `@Shape` class: the declarations (class, supertypes, constructors, member
 * signatures) a plugin generates in FIR, without bodies. Bodies are the IR phase's job (templates).
 *
 * Types use the same [TType] model as templates; [TType.TypeParameter] refers to the shape's own type
 * parameters, which are substituted when the shape is used (see `boberc.fir.bind`), and [TType.This] is the
 * generated class.
 */
public class ClassShape(
    public val name: String,
    public val kind: TClassKind,
    public val visibility: TVisibility,
    /** `open class` shapes generate open classes; every other class shape generates a final class. */
    public val isOpen: Boolean,
    public val typeParameters: List<TTypeParameter>,
    /** Declared supertypes, without the implicit `kotlin.Any`. */
    public val supertypes: List<TType>,
    public val constructors: List<ConstructorShape>,
    public val members: List<MemberShape>,
) {
    /** Names of the members in declaration order (constructors excluded). */
    public val memberNames: List<String> get() = members.map { it.name }.distinct()

    public fun membersNamed(name: String): List<MemberShape> = members.filter { it.name == name }

    public fun encode(): String = TemplateCodec.encode(this)

    /** A readable rendering of the shape model (indented S-expression), handy in tests. */
    public fun render(): String = SExpr.pretty(TemplateCodec.encodeToSExpr(this))

    override fun toString(): String = "ClassShape($name, ${members.size} members)"

    public companion object {
        public fun decode(encoded: String): ClassShape = TemplateCodec.decodeShape(encoded)
    }
}

public enum class TClassKind { CLASS, INTERFACE, OBJECT }

public enum class TModality { FINAL, OPEN, ABSTRACT }

public class ConstructorShape(
    public val visibility: TVisibility,
    public val isPrimary: Boolean,
    public val parameters: List<TParameter>,
)

/** A member signature. [modality] is the one declared in the shape (see `boberc.fir` for how it maps). */
public sealed class MemberShape {
    public abstract val name: String
    public abstract val visibility: TVisibility
    public abstract val modality: TModality
    public abstract val isOverride: Boolean

    public class Property(
        override val name: String,
        override val visibility: TVisibility,
        override val modality: TModality,
        override val isOverride: Boolean,
        public val type: TType,
        public val isVar: Boolean,
    ) : MemberShape()

    public class Function(
        override val name: String,
        override val visibility: TVisibility,
        override val modality: TModality,
        override val isOverride: Boolean,
        public val isOperator: Boolean,
        public val isInfix: Boolean,
        public val isExternal: Boolean,
        public val receiverType: TType?,
        /** A parameter with [TParameter.isSplice] marks where plugin-supplied parameters are inserted. */
        public val parameters: List<TParameter>,
        public val returnType: TType,
    ) : MemberShape()
}
