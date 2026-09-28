import org.apache.avro.compiler.specific.SpecificCompiler
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates Java classes from Avro `.avsc` schema files.
 *
 * ## Why a custom task class rather than a plugin or a JavaExec
 *
 * The usual Avro Gradle plugin's support for Gradle 9 is unverified, and a learning project
 * should not be blocked on a third party's release cadence.
 *
 * Driving the CLI with `JavaExec` was tried first and rejected: `avro-tools` 1.12.x cannot be
 * resolved at all (its POM declares `trevni-*-tests.jar` artifacts that were never published
 * to Maven Central), and `SpecificCompiler.main` defaults to *protocol* mode, so feeding it a
 * record schema fails with "No protocol name specified".
 *
 * `SpecificCompiler.compileSchema(File[], File)` is the actual API underneath all of that.
 * Calling it directly is shorter, has no CLI archaeology, and gives real error messages.
 *
 * ## Why this is a class with annotated properties
 *
 * The `@InputDirectory` / `@OutputDirectory` annotations are what make the task
 * **incremental** and **cacheable**: Gradle fingerprints the inputs and skips the task when
 * nothing changed. Without them, codegen would run on every build, forever.
 *
 * `@PathSensitive(RELATIVE)` says only the paths *relative* to the schema directory matter —
 * so the cache still hits when the project is checked out somewhere else, which is exactly
 * what makes a shared build cache useful in CI.
 */
abstract class AvroCodegenTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        // Clear stale output: a schema that was deleted must not leave its generated class
        // behind, or the build keeps compiling a type nobody declares any more.
        out.deleteRecursively()
        out.mkdirs()

        val schemas = schemaDir
            .get()
            .asFile
            .listFiles { f -> f.isFile && f.name.endsWith(".avsc") }
            ?.sortedBy { it.name } // deterministic order ⇒ reproducible output
            ?: emptyList()

        if (schemas.isEmpty()) {
            logger.warn("No .avsc files found in ${schemaDir.get().asFile}")
            return
        }

        // compileSchema takes the whole array at once, so schemas may reference types
        // declared in each other. One file at a time would break cross-schema references.
        SpecificCompiler.compileSchema(schemas.toTypedArray(), out)

        logger.lifecycle("Generated Avro sources from ${schemas.size} schema(s) into $out")
    }
}
