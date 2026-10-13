/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaPairCompatibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The schema-evolution gate.
 *
 * <h2>What this protects against</h2>
 *
 * Across a message bus, nothing type-checks a producer against its consumers. A producer can
 * ship a "harmless" field rename on Tuesday, and a consumer that only runs on Sunday fails in
 * production with a stack trace that names neither the change nor the person who made it.
 *
 * <p>This test makes that failure happen at <b>build</b> time instead — in the pull request
 * that caused it.
 *
 * <h2>How Avro compatibility actually works</h2>
 *
 * Avro resolves a <em>reader</em> schema against a <em>writer</em> schema, field by field,
 * <b>by name</b>:
 *
 * <pre>
 *   field present in both            → read it
 *   field in the writer only         → skip it
 *   field in the reader only         → use the READER'S DEFAULT
 *                                      ...and if it has no default: ERROR
 * </pre>
 *
 * That last line is the entire rule, and it explains every row of the table in
 * {@code specs/01-contracts/design.md} §4.
 *
 * <h2>Why BACKWARD</h2>
 *
 * BACKWARD compatibility means <b>a new reader can read old data</b>. We check
 * {@code reader = current schema}, {@code writer = frozen baseline}.
 *
 * <p>It is the right mode for a telemetry pipeline because history is retained and replayed:
 * a newly deployed consumer must be able to read everything already sitting on the topic and
 * in the S3 archive. It also implies the deployment order — <b>consumers first, then
 * producers</b>.
 *
 * <p>No Schema Registry is needed: {@link SchemaCompatibility} ships inside Avro, so this runs
 * as an ordinary unit test with no Docker and no network. A real registry arrives in Phase 2
 * and enforces the same rule at runtime — but catching it here is strictly better, because a
 * failed build is cheaper than a failed deployment.
 */
class SchemaCompatibilityTest {

    private static final Path CURRENT_SCHEMA_DIR = Path.of("src/main/avro");

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Schema parse(Path path) throws IOException {
        // A fresh Parser per schema: Parser instances accumulate named types, so reusing one
        // across schemas makes a later parse succeed only because an earlier one defined a
        // type. That would make this test pass for the wrong reason.
        return new Schema.Parser().parse(Files.readString(path));
    }

    private static Schema parseResource(String resource) throws IOException {
        try (InputStream in = SchemaCompatibilityTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + resource);
            }
            return new Schema.Parser().parse(new String(in.readAllBytes()));
        }
    }

    /**
     * Checks BACKWARD compatibility: can {@code reader} read data written by {@code writer}?
     */
    private static SchemaPairCompatibility check(Schema reader, Schema writer) {
        return SchemaCompatibility.checkReaderWriterCompatibility(reader, writer);
    }

    private static boolean isCompatible(SchemaPairCompatibility result) {
        return result.getType() == SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE;
    }

    /**
     * Summarises incompatibilities as {@code TYPE at /path (field)}.
     *
     * <p>Avro's own {@code toString} embeds BOTH complete schemas in every entry, which for
     * our event model is roughly 10 KB of JSON per failure. A developer scrolling past that
     * to find the field name is a developer who stops reading test output. The three things
     * that actually matter are the rule that was broken, where, and which field.
     */
    private static String explain(SchemaPairCompatibility result) {
        return result.getResult().getIncompatibilities().stream()
                .map(i -> "  - %s at %s (%s)".formatted(i.getType(), i.getLocation(), i.getMessage()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("every current schema stays BACKWARD compatible with its frozen baseline")
    class BaselineCompatibility {

        @ParameterizedTest(name = "{0} can read data written by its baseline")
        @ValueSource(strings = {"TelemetryEvent", "MetricAggregate", "AlertEvent", "ConfigChange", "WatchEvent"})
        void currentSchemaCanReadBaselineData(String schemaName) throws IOException {
            Schema current = parse(CURRENT_SCHEMA_DIR.resolve(schemaName + ".avsc"));
            Schema baseline = parseResource("baseline/" + schemaName + ".avsc");

            SchemaPairCompatibility result = check(current, baseline);

            // The failure message names the exact field and the reason, so a developer who
            // breaks this does not have to go and learn Avro's resolution rules first.
            assertThat(isCompatible(result))
                    .as(
                            "%s is NOT backward compatible with its baseline.%n%s%n%n"
                                    + "Most likely you added a field without a default, renamed one, "
                                    + "or narrowed a type. See specs/01-contracts/design.md section 4.%n"
                                    + "If the change is genuinely intended, update the baseline in "
                                    + "src/test/resources/baseline/ IN THE SAME COMMIT so a reviewer "
                                    + "sees both halves.",
                            schemaName, explain(result))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("the gate is proven — it REJECTS genuinely breaking changes")
    class GateProof {

        /**
         * A test suite that has never been seen to fail is not evidence of anything. These
         * cases keep the gate honest: if someone weakens the check, these go green and the
         * weakening is visible.
         */
        @ParameterizedTest(name = "{0} is correctly REJECTED")
        @ValueSource(strings = {"AddedRequiredField", "RenamedField", "NarrowedType"})
        void breakingChangesAreRejected(String fixture) throws IOException {
            Schema breaking = parseResource("incompatible/" + fixture + ".avsc");
            Schema baseline = parseResource("baseline/TelemetryEvent.avsc");

            SchemaPairCompatibility result = check(breaking, baseline);

            assertThat(isCompatible(result))
                    .as("%s SHOULD be rejected but the gate accepted it — the gate is broken", fixture)
                    .isFalse();
            assertThat(result.getResult().getIncompatibilities()).isNotEmpty();
        }

        @Test
        @DisplayName("adding a field WITH a default is accepted — the nuance that matters")
        void addingAnOptionalFieldIsSafe() throws IOException {
            // The contrast with AddedRequiredField is the whole lesson: it is not "adding
            // fields is dangerous", it is "adding a field WITHOUT A DEFAULT is dangerous".
            Schema evolved = parseResource("incompatible/AddedOptionalField.avsc");
            Schema baseline = parseResource("baseline/TelemetryEvent.avsc");

            assertThat(isCompatible(check(evolved, baseline)))
                    .as("a union-with-null field carrying a default must be backward compatible")
                    .isTrue();
        }

        @Test
        @DisplayName("the rejection message names the offending field")
        void failureIsDiagnosable() throws IOException {
            Schema breaking = parseResource("incompatible/AddedRequiredField.avsc");
            Schema baseline = parseResource("baseline/TelemetryEvent.avsc");

            var incompatibilities = check(breaking, baseline).getResult().getIncompatibilities();

            // A gate that says only "incompatible" sends people to Stack Overflow. One that
            // names the field and the rule sends them to the fix.
            assertThat(incompatibilities).isNotEmpty();
            assertThat(incompatibilities.toString())
                    .contains("mandatoryNewField")
                    .containsIgnoringCase("default");
        }
    }

    @Nested
    @DisplayName("every schema is self-consistent")
    class Wellformedness {

        @Test
        @DisplayName("a baseline exists for every schema — no schema is silently ungated")
        void everySchemaHasABaseline() throws IOException {
            // Without this, adding a new schema and forgetting its baseline would leave it
            // completely unprotected, and nothing would ever tell you.
            try (Stream<Path> schemas = Files.list(CURRENT_SCHEMA_DIR)) {
                var names = schemas.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".avsc"))
                        .sorted()
                        .toList();

                assertThat(names).isNotEmpty();
                for (String name : names) {
                    assertThat(getClass().getClassLoader().getResource("baseline/" + name))
                            .as("No frozen baseline for %s — add one to src/test/resources/baseline/", name)
                            .isNotNull();
                }
            }
        }

        @ParameterizedTest(name = "{0} is a trivially self-compatible schema")
        @ValueSource(strings = {"TelemetryEvent", "MetricAggregate", "AlertEvent", "ConfigChange", "WatchEvent"})
        void schemaIsCompatibleWithItself(String schemaName) throws IOException {
            // A sanity check on the checker: if a schema were not compatible with itself,
            // the test above would be meaningless.
            Schema s = parse(CURRENT_SCHEMA_DIR.resolve(schemaName + ".avsc"));
            assertThat(isCompatible(check(s, s))).isTrue();
        }
    }
}
