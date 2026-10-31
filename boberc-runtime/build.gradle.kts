plugins {
    alias(libs.plugins.kotlin.jvm)
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
    }
    test {
        java.setSrcDirs(listOf("test"))
    }
}

dependencies {
    // The FIR/IR materializers run inside the compiler of the *target* compilation,
    // so the compiler is provided by the environment.
    compileOnly(libs.kotlin.compiler)

    testImplementation(libs.kotlin.test.junit5)
}

kotlin {
    explicitApi()
}

tasks.compileKotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
        optIn.add("org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi")
        optIn.add("boberc.BobercInternalApi")
    }
}

tasks.test {
    useJUnitPlatform()
}
