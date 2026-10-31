package boberc.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Applies boberc to a module that implements a Kotlin compiler plugin:
 *  - adds `boberc-runtime` (template API + FIR/IR materializers) as a dependency,
 *  - runs the boberc compiler plugin, which captures `@Template` functions into template models.
 */
@Suppress("unused") // Used via reflection.
class BobercGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {}

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = BuildConfig.KOTLIN_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = BuildConfig.KOTLIN_PLUGIN_GROUP,
        artifactId = BuildConfig.KOTLIN_PLUGIN_NAME,
        version = BuildConfig.KOTLIN_PLUGIN_VERSION,
    )

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        kotlinCompilation.defaultSourceSet.dependencies {
            implementation(BuildConfig.RUNTIME_LIBRARY_COORDINATES)
        }
        return kotlinCompilation.target.project.provider { emptyList() }
    }
}
