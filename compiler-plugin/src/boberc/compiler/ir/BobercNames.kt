package boberc.compiler.ir

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/** Names of the boberc API the compiler plugin recognizes (`boberc-runtime`, package `boberc`). */
internal object BobercNames {
  val TEMPLATE_ANNOTATION = FqName("boberc.Template")
  val SPLICE_ANNOTATION = FqName("boberc.Splice")
  val THIS_CLASS_ID: ClassId = ClassId.topLevel(FqName("boberc.This"))
  val DYNAMIC_CLASS_ID: ClassId = ClassId.topLevel(FqName("boberc.Dynamic"))
  val MIRROR_ANNOTATION = FqName("boberc.Mirror")
  val TEMPLATE_PARAMETER_CLASS_ID: ClassId = ClassId.topLevel(FqName("boberc.TemplateParameter"))
  val TARGET_FUNCTION_CLASS_ID: ClassId = ClassId.topLevel(FqName("boberc.TargetFunction"))
  val TARGET_FUNCTION = FqName("boberc.targetFunction")
  val FUNCTION_REF_CLASS_ID: ClassId = ClassId.topLevel(FqName("boberc.FunctionRef"))
  val EXPR_REF_CLASS_ID: ClassId = ClassId.topLevel(FqName("boberc.ExprRef"))
  val EXPR_LIST_REF_CLASS_ID: ClassId = ClassId.topLevel(FqName("boberc.ExprListRef"))
  val TYPE_REF_CLASS_ID: ClassId = ClassId.topLevel(FqName("boberc.TypeRef"))
  val FIELD_REF_CLASS_ID: ClassId = ClassId.topLevel(FqName("boberc.FieldRef"))

  /** Splice handles: IR handed over by the plugin, captured as splices and never materialized as types. */
  val HANDLE_CLASS_IDS = setOf(FUNCTION_REF_CLASS_ID, EXPR_REF_CLASS_ID, EXPR_LIST_REF_CLASS_ID, TYPE_REF_CLASS_ID, FIELD_REF_CLASS_ID)

  /** Class ids of the types a splice may have: handles, primitives and `String`. */
  val SPLICEABLE_TYPES: Set<String> = HANDLE_CLASS_IDS.mapTo(HashSet()) { it.asString() } + setOf(
    "kotlin/String", "kotlin/Int", "kotlin/Long", "kotlin/Short", "kotlin/Byte",
    "kotlin/Char", "kotlin/Boolean", "kotlin/Float", "kotlin/Double",
  )
  val BOBERC_PACKAGE = FqName("boberc")
  val TEMPLATE_INTRINSIC = FqName("boberc.template")
  val ARG_INTRINSIC = FqName("boberc.arg")
  val TYPE_ARGS_INTRINSIC = FqName("boberc.typeArgs")
  val DECODE_FUNCTION_PACKAGE = FqName("boberc.internal")
  const val DECODE_FUNCTION_NAME = "decodeFunctionTemplate"
  const val DECODE_LAMBDA_NAME = "decodeLambdaTemplate"
  val SHAPE_ANNOTATION = FqName("boberc.Shape")
  val TEMPORARY_INTRINSIC = FqName("boberc.temporary")
  val PARAMETER_SPLICE_CLASS_ID: ClassId = ClassId.topLevel(FqName("boberc.ParameterSplice"))
  val SHAPE_INTRINSIC = FqName("boberc.shape")
  const val DECODE_SHAPE_NAME = "decodeClassShape"
}
