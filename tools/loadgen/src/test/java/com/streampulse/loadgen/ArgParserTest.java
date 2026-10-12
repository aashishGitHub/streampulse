/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ArgParserTest {

    @Test
    void noArgumentsYieldsDefaults() {
        LoadGenConfig config = ArgParser.parse(new String[] {});

        assertThat(config.batch()).isEqualTo(100);
        assertThat(config.ingestUrl()).isEqualTo("http://localhost:8080");
        assertThat(config.keycloakUrl()).isEqualTo("http://localhost:8081");
        assertThat(config.freshnessUrl()).isEqualTo("http://localhost:8084/actuator/prometheus");
        assertThat(config.lateFraction()).isZero();
    }

    @Test
    void parsesSpaceSeparatedAndEqualsForms() {
        LoadGenConfig config = ArgParser.parse(new String[] {
            "--rate", "2000", "--duration=60", "--batch", "250", "--sessions", "50", "--late-fraction=0.1"
        });

        assertThat(config.rate()).isEqualTo(2000);
        assertThat(config.durationSeconds()).isEqualTo(60);
        assertThat(config.batch()).isEqualTo(250);
        assertThat(config.sessions()).isEqualTo(50);
        assertThat(config.lateFraction()).isEqualTo(0.1);
    }

    @Test
    void stripsTrailingSlashFromUrls() {
        LoadGenConfig config = ArgParser.parse(new String[] {
            "--url", "http://gw:8080/", "--keycloak", "http://kc:8081/", "--freshness-url", "http://sink:8084/m"
        });

        assertThat(config.ingestUrl()).isEqualTo("http://gw:8080");
        assertThat(config.keycloakUrl()).isEqualTo("http://kc:8081");
        assertThat(config.freshnessUrl()).isEqualTo("http://sink:8084/m");
    }

    @Test
    void batchMayBeExactlyTheLimits() {
        assertThat(ArgParser.parse(new String[] {"--batch", "1"}).batch()).isEqualTo(1);
        assertThat(ArgParser.parse(new String[] {"--batch", "500"}).batch()).isEqualTo(500);
    }

    @Test
    void rejectsBatchAboveTheApiLimit() {
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--batch", "501"}))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("--batch");
    }

    @Test
    void rejectsBatchOfZero() {
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--batch", "0"}))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("--batch");
    }

    @Test
    void rejectsNonPositiveRateAndDurationAndSessions() {
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--rate", "0"}))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("--rate");
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--duration", "-5"}))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("--duration");
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--sessions", "0"}))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("--sessions");
    }

    @Test
    void rejectsNonNumericValue() {
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--rate", "fast"}))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("--rate")
                .hasMessageContaining("fast");
    }

    @Test
    void rejectsLateFractionOutsideZeroToOne() {
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--late-fraction", "1.5"}))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("--late-fraction");
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--late-fraction", "-0.1"}))
                .isInstanceOf(ArgumentException.class);
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--late-fraction", "NaN"}))
                .isInstanceOf(ArgumentException.class);
    }

    @Test
    void rejectsUnknownFlag() {
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--bogus", "1"}))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("--bogus");
    }

    @Test
    void rejectsFlagMissingItsValue() {
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--rate"}))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("--rate");
    }

    @Test
    void rejectsPositionalArgument() {
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"2000"})).isInstanceOf(ArgumentException.class);
    }

    @Test
    void rejectsUrlWithoutHttpScheme() {
        assertThatThrownBy(() -> ArgParser.parse(new String[] {"--url", "localhost:8080"}))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("--url");
    }
}
