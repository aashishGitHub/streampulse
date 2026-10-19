/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.compactor.adapter.in.CompactionRequest;
import com.streampulse.compactor.domain.CompactionReport;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.function.context.FunctionCatalog;

/** No Docker: the context assembles and the function is reachable by name, without touching a network. */
@SpringBootTest(
        classes = com.streampulse.compactor.CompactorApplication.class,
        properties = {"compactor.bucket=wiring-bucket", "compactor.endpoint-override=http://localhost:9"})
class CompactorFunctionWiringTest {

    @Autowired
    private FunctionCatalog catalog;

    @Autowired
    private CompactorProperties properties;

    @Test
    @DisplayName("the compactor is registered in the function catalog under its bean name")
    void functionIsInTheCatalog() {
        Function<CompactionRequest, CompactionReport> function = catalog.lookup(Function.class, "compactArchive");

        assertThat(function).isNotNull();
    }

    @Test
    @DisplayName("the quiet period defaults to 15 minutes and the minimum input count to 2")
    void defaults() {
        assertThat(properties.quietPeriod()).hasMinutes(15);
        assertThat(properties.minInputObjects()).isEqualTo(2);
        assertThat(properties.isLocalSimulation()).isTrue();
    }
}
