/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** An in-JVM stand-in for query-api or alert-engine. Records every request so tests can assert "GET only". */
public final class FakeBackend implements AutoCloseable {

    public record Request(String method, URI uri) {}

    public record Response(int status, String contentType, byte[] body, Duration delay) {

        public static Response json(String body) {
            return new Response(200, "application/json", body.getBytes(StandardCharsets.UTF_8), Duration.ZERO);
        }

        public static Response status(int status, String contentType, String body) {
            return new Response(status, contentType, body.getBytes(StandardCharsets.UTF_8), Duration.ZERO);
        }

        public static Response slow(Duration delay) {
            return new Response(200, "application/json", "[]".getBytes(StandardCharsets.UTF_8), delay);
        }

        public static Response bytes(byte[] body) {
            return new Response(200, "application/json", body, Duration.ZERO);
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Response> responder = r -> Response.status(404, "text/plain", "none");

    private FakeBackend(HttpServer server) {
        this.server = server;
    }

    public static FakeBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeBackend fake = new FakeBackend(server);
            server.createContext("/", exchange -> {
                Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI());
                fake.requests.add(request);
                Response response = fake.responder.apply(request);
                try {
                    if (!response.delay().isZero()) {
                        Thread.sleep(response.delay().toMillis());
                    }
                    exchange.getResponseHeaders().add("Content-Type", response.contentType());
                    exchange.sendResponseHeaders(response.status(), response.body().length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(response.body());
                    }
                } catch (IOException | InterruptedException ignoredClientWentAway) {
                    // the client may cancel on purpose (timeout, oversized body)
                } finally {
                    exchange.close();
                }
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public FakeBackend respond(Function<Request, Response> responder) {
        this.responder = responder;
        return this;
    }

    public FakeBackend respondWith(Response response) {
        return respond(r -> response);
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public List<Request> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** A URI nobody listens on: the port of a server that was started and stopped. */
    public static URI deadUri() {
        FakeBackend fake = start();
        URI uri = fake.baseUri();
        fake.close();
        return uri;
    }
}
