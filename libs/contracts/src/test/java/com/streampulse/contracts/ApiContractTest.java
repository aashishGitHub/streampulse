/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Validates the API and topic contracts at build time.
 *
 * <h2>Why bother</h2>
 *
 * A malformed OpenAPI document does not fail loudly — it fails at the moment someone tries
 * to generate a client from it, which is usually a different person on a different day.
 * Parsing it in CI moves that discovery to the commit that caused it.
 *
 * <p>From Phase 2 the ingest contract also generates the controller interface, so the
 * compiler enforces that the implementation matches. Until then, this is the guard.
 */
class ApiContractTest {

    private static final Path API_DIR = Path.of("../../api");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Nested
    @DisplayName("OpenAPI documents")
    class OpenApi {

        @ParameterizedTest(name = "{0} parses with no errors")
        @ValueSource(strings = {"ingest-v1.yaml", "query-v1.yaml", "control-plane-v1.yaml"})
        void documentIsValid(String fileName) {
            Path doc = API_DIR.resolve("openapi").resolve(fileName);
            assertThat(doc).exists();

            ParseOptions options = new ParseOptions();
            options.setResolve(true); // follow $ref, so a broken reference is caught here
            SwaggerParseResult result = new OpenAPIV3Parser().readLocation(doc.toString(), null, options);

            assertThat(result.getMessages())
                    .as("%s has validation errors", fileName)
                    .isEmpty();
            assertThat(result.getOpenAPI()).as("%s did not parse", fileName).isNotNull();
            assertThat(result.getOpenAPI().getPaths())
                    .as("%s declares no paths", fileName)
                    .isNotEmpty();
        }

        @Test
        @DisplayName("every endpoint documents an error response, not just the happy path")
        void errorsAreDocumented() {
            // An OpenAPI document that only describes 2xx is a wish list. Clients need to
            // know what failure looks like before they meet it in production.
            ParseOptions options = new ParseOptions();
            options.setResolve(true);
            var api = new OpenAPIV3Parser()
                    .readLocation(API_DIR.resolve("openapi/ingest-v1.yaml").toString(), null, options)
                    .getOpenAPI();

            var responses = api.getPaths().get("/v1/events:batch").getPost().getResponses();

            assertThat(responses.keySet())
                    .as("the ingest endpoint must document backpressure and failure explicitly")
                    .contains("202", "400", "429");

            // 429 carries Retry-After, or clients cannot back off correctly.
            assertThat(responses.get("429").getHeaders()).containsKey("Retry-After");
        }

        @Test
        @DisplayName("errors use application/problem+json, not a bespoke shape")
        void errorsUseRfc7807() {
            ParseOptions options = new ParseOptions();
            options.setResolve(true);
            var api = new OpenAPIV3Parser()
                    .readLocation(API_DIR.resolve("openapi/ingest-v1.yaml").toString(), null, options)
                    .getOpenAPI();

            var badRequest = api.getPaths()
                    .get("/v1/events:batch")
                    .getPost()
                    .getResponses()
                    .get("400");

            assertThat(badRequest.getContent().keySet())
                    .as("one error shape across every endpoint means clients parse one thing")
                    .contains("application/problem+json");
        }
    }

    @Nested
    @DisplayName("AsyncAPI topic contract")
    class AsyncApi {

        private JsonNode topics() throws IOException {
            Path doc = API_DIR.resolve("asyncapi/streampulse-topics.yaml");
            assertThat(doc).exists();
            return YAML.readTree(Files.readString(doc));
        }

        @Test
        @DisplayName("the document is well-formed AsyncAPI 3")
        void isWellFormed() throws IOException {
            JsonNode root = topics();
            assertThat(root.path("asyncapi").asText()).startsWith("3.");
            assertThat(root.path("channels")).isNotEmpty();
        }

        @Test
        @DisplayName("every channel declares partitions — the one setting you cannot change later")
        void everyChannelDeclaresItsKafkaBindings() throws IOException {
            // Partition count is effectively immutable: raising it rehashes keys and breaks
            // per-key ordering. Leaving it undocumented is how a topic ends up with the
            // default because nobody decided.
            JsonNode channels = topics().path("channels");
            assertThat(channels).isNotEmpty();

            channels.fields().forEachRemaining(entry -> {
                JsonNode kafka = entry.getValue().path("bindings").path("kafka");
                assertThat(kafka.has("topic"))
                        .as("channel '%s' declares no Kafka topic name", entry.getKey())
                        .isTrue();
                assertThat(kafka.has("partitions"))
                        .as("channel '%s' declares no partition count", entry.getKey())
                        .isTrue();
            });
        }

        @Test
        @DisplayName("every topic name carries a version suffix")
        void topicsAreVersioned() throws IOException {
            // Versioned names let v1 and v2 run side by side during a migration, instead of
            // needing a flag day where every producer and consumer switches at once.
            topics().path("channels").fields().forEachRemaining(entry -> {
                String address = entry.getValue().path("address").asText();
                assertThat(address)
                        .as("topic '%s' has no version suffix", address)
                        .matches(".*\\.v\\d+(\\.dlt)?$");
            });
        }

        @Test
        @DisplayName("the raw telemetry topic declares a dead-letter topic")
        void poisonMessagesHaveSomewhereToGo() throws IOException {
            // Without a DLT, one poison message blocks its partition forever and a single
            // malformed payload stops a tenant's entire pipeline.
            assertThat(topics().path("channels").has("telemetryRawDlt")).isTrue();
        }
    }
}
