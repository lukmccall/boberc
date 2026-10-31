package boberc.example

import boberc.example.runners.AbstractExampleBoxTest
import boberc.example.runners.AbstractExampleDiagnosticTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

fun main(args: Array<String>) {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(testsRoot = args[0], testDataRoot = args[1]) {
            testClass<AbstractExampleDiagnosticTest> {
                model("diagnostics")
            }
            testClass<AbstractExampleBoxTest> {
                model("box")
            }
        }
    }
}
