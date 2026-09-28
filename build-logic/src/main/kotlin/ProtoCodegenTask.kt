import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Generates Java message classes and gRPC service stubs from `.proto` files.
 *
 * ## Why a custom task rather than the `com.google.protobuf` Gradle plugin
 *
 * Same reasoning as [AvroCodegenTask], and the same evidence: that plugin's support for
 * Gradle 9 is unverified, and a learning project should not be blocked on someone else's
 * release cadence. Phase 1 already hit exactly this with the Avro plugin.
 *
 * The thing the plugin mostly does for you is *find a protoc binary*. That turns out to be
 * a solved problem: Google publish `protoc` to Maven Central as a platform-specific
 * executable, and gRPC publish their codegen plugin the same way. So an ordinary Gradle
 * configuration can resolve both, and this task just runs them.
 *
 * ```
 *   com.google.protobuf:protoc:3.25.9:osx-aarch_64@exe      ──┐
 *   io.grpc:protoc-gen-grpc-java:1.84.0:osx-aarch_64@exe    ──┤
 *   com.google.protobuf:protobuf-java:3.25.9  (a jar) ────────┤
 *                                                             ▼
 *                        protoc -I src/main/proto -I <well-known types>
 *                               --java_out      → message classes
 *                               --grpc-java_out → service stubs
 * ```
 *
 * ## The one non-obvious part: the well-known types
 *
 * `import "google/protobuf/timestamp.proto"` does **not** work out of the box. The standalone
 * protoc binary does not embed the well-known types — verified by running it:
 *
 * ```
 *   google/protobuf/timestamp.proto: File not found.
 * ```
 *
 * Those `.proto` files ship *inside the `protobuf-java` jar*. Unpacking them and adding the
 * directory to protoc's include path is exactly what the official plugin does under the hood,
 * and it is four lines here. Doing it this way also guarantees the definitions match the
 * runtime we compile against, because they come from the same artifact.
 *
 * ## Why injected services rather than `project.exec`
 *
 * `project.exec { }` inside a task action reaches back into the Project object at execution
 * time, which Gradle's configuration cache forbids (and Gradle 9 is strict about). The
 * supported form is to inject [ExecOperations], [ArchiveOperations] and
 * [FileSystemOperations]. Same capability, no Project reference.
 */
abstract class ProtoCodegenTask : DefaultTask() {

    /** The `.proto` sources. RELATIVE sensitivity so the build cache still hits elsewhere. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val protoDir: DirectoryProperty

    /**
     * The protoc executable, as a resolved configuration.
     *
     * A [ConfigurableFileCollection] rather than a `RegularFileProperty` because a Gradle
     * configuration resolves to a *set* of files; the task picks the single element. NAME_ONLY
     * sensitivity: the binary lives at a machine-specific path in the Gradle cache, so
     * fingerprinting the absolute path would miss the cache on every other machine.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val protocExecutable: ConfigurableFileCollection

    /** The gRPC codegen plugin executable. Without it protoc emits messages but no stubs. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val grpcPluginExecutable: ConfigurableFileCollection

    /** The `protobuf-java` jar, which carries the well-known-type `.proto` definitions. */
    @get:Classpath
    abstract val wellKnownTypes: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        // Clear stale output, for the same reason AvroCodegenTask does: a deleted .proto must
        // not leave its generated class behind for the compiler to keep finding.
        out.deleteRecursively()
        out.mkdirs()

        val protoFiles = protoDir
            .get()
            .asFile
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".proto") }
            .sortedBy { it.path } // deterministic order ⇒ reproducible output
            .toList()

        if (protoFiles.isEmpty()) {
            logger.warn("No .proto files found in ${protoDir.get().asFile}")
            return
        }

        val wellKnownDir = temporaryDir.resolve("well-known-types")
        fileSystemOperations.copy {
            from(archiveOperations.zipTree(wellKnownTypes.singleFile))
            include("google/protobuf/*.proto")
            into(wellKnownDir)
        }

        // Maven hands us a file with no execute bit — it was never a "file" to Maven, just
        // bytes. Without this, exec fails with a permission error that reads like a bug in
        // the build rather than a missing chmod.
        val protoc = protocExecutable.singleFile.also { it.setExecutable(true) }
        val grpcPlugin = grpcPluginExecutable.singleFile.also { it.setExecutable(true) }

        execOperations.exec {
            commandLine(
                buildList {
                    add(protoc.absolutePath)
                    add("-I=${protoDir.get().asFile.absolutePath}")
                    add("-I=${wellKnownDir.absolutePath}")
                    add("--java_out=${out.absolutePath}")
                    add("--plugin=protoc-gen-grpc-java=${grpcPlugin.absolutePath}")
                    add("--grpc-java_out=${out.absolutePath}")
                    addAll(protoFiles.map { it.absolutePath })
                }
            )
        }

        logger.lifecycle("Generated protobuf + gRPC sources from ${protoFiles.size} file(s) into $out")
    }
}
