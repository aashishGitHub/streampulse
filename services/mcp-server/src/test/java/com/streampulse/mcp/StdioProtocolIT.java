/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp;

import static com.streampulse.mcp.support.ToolsFixture.JSON;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.streampulse.mcp.support.FakeBackend;
import com.streampulse.mcp.support.ServerProcess;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * stdout is the protocol channel. A banner or a log line on it corrupts the stream, and the symptom in a
 * client is a hang or "failed to connect" with no explanation. This reads the raw bytes, no SDK in between.
 */
class StdioProtocolIT {

    @Test
    @DisplayName("stdout carries only JSON-RPC messages, one per line; logs and the banner are not on it")
    void stdoutCarriesOnlyProtocolMessages() throws Exception {
        try (FakeBackend queryApi = FakeBackend.start();
                FakeBackend alertEngine = FakeBackend.start()) {
            ProcessBuilder pb = new ProcessBuilder(ServerProcess.command());
            pb.environment().putAll(ServerProcess.env(queryApi.baseUri(), alertEngine.baseUri()));
            Process process = pb.start();
            BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader in =
                        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        stdout.add(line);
                    }
                } catch (Exception ignored) {
                    // process ended
                }
            });
            try {
                BufferedWriter in =
                        new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                send(
                        in,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"raw-it\",\"version\":\"1\"}}}");
                String first = stdout.poll(60, TimeUnit.SECONDS);
                assertThat(first).as("first line on stdout").isNotNull();
                send(in, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                send(in, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
                String second = stdout.poll(30, TimeUnit.SECONDS);
                assertThat(second).as("second line on stdout").isNotNull();

                List<String> lines = new ArrayList<>(List.of(first, second));
                for (String line : lines) {
                    JsonNode message = JSON.readTree(line);
                    assertThat(message.path("jsonrpc").asText()).isEqualTo("2.0");
                }
                assertThat(JSON.readTree(first).at("/result/serverInfo/name").asText())
                        .isEqualTo("streampulse-mcp");
                assertThat(JSON.readTree(second).at("/result/tools")).hasSize(3);

                Thread.sleep(500);
                stdout.drainTo(lines);
                assertThat(lines).allSatisfy(line -> assertThat(
                                JSON.readTree(line).path("jsonrpc").asText())
                        .isEqualTo("2.0"));
            } finally {
                process.destroy();
                process.waitFor(10, TimeUnit.SECONDS);
                reader.join(5_000);
            }
        }
    }

    private static void send(BufferedWriter out, String json) throws Exception {
        out.write(json);
        out.write("\n");
        out.flush();
    }
}
