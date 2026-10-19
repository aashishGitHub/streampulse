/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * libs/contracts' ApiContractTest lists the documents it validates by name and does not include
 * alerts-v1.yaml, so this module validates its own contract: it parses, and every operation
 * documents a 4xx problem+json response.
 */
class AlertsApiContractTest {

    static final Path DOCUMENT = Path.of("../../api/openapi/alerts-v1.yaml");

    @Test
    @DisplayName("alerts-v1.yaml parses with no errors and declares both operations")
    void parses() {
        var options = new ParseOptions();
        options.setResolve(true);

        var result = new OpenAPIV3Parser().readLocation(DOCUMENT.toString(), null, options);

        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI().getPaths()).containsKeys("/v1/alerts", "/v1/alerts/stream");
    }

    @Test
    @DisplayName("every operation documents a 400 as application/problem+json")
    void everyOperationDocumentsProblemJson() {
        var options = new ParseOptions();
        options.setResolve(true);
        var api = new OpenAPIV3Parser()
                .readLocation(DOCUMENT.toString(), null, options)
                .getOpenAPI();

        List<PathItem> items = List.copyOf(api.getPaths().values());
        assertThat(items).hasSize(2);
        for (PathItem item : items) {
            var responses = item.getGet().getResponses();
            assertThat(responses.get("400").getContent()).containsKey("application/problem+json");
        }
    }

    @Test
    @DisplayName("the stream operation is text/event-stream")
    void streamIsEventStream() {
        var api = new OpenAPIV3Parser()
                .readLocation(DOCUMENT.toString(), null, new ParseOptions())
                .getOpenAPI();

        assertThat(api.getPaths()
                        .get("/v1/alerts/stream")
                        .getGet()
                        .getResponses()
                        .get("200")
                        .getContent())
                .containsKey("text/event-stream");
    }
}
