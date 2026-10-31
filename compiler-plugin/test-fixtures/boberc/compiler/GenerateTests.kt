package boberc.compiler

import boberc.compiler.runners.AbstractJvmBoxTest
import boberc.compiler.runners.AbstractJvmDiagnosticTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

fun main(args: Array<String>) {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(testsRoot = args[0], testDataRoot = args[1]) {
            testClass<AbstractJvmDiagnosticTest> {
                model("diagnostics")
            }
            testClass<AbstractJvmBoxTest> {
                model("box")
            }
        }
    }
}
