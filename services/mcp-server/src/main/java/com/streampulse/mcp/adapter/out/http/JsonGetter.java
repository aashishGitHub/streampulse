/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.adapter.out.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.mcp.application.BackendFailure;
import com.streampulse.mcp.application.BackendFailure.Category;
import com.streampulse.mcp.domain.Backend;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The only place that talks HTTP. It can only build GET requests (ArchUnit forbids the other verbs in this
 * package), bounds total time and body size, and turns every failure into a {@link BackendFailure} whose
 * message holds fixed words only.
 */
public final class JsonGetter {

    private final HttpClient client;
    private final Duration timeout;
    private final int maxBodyBytes;
    private final ObjectMapper mapper;

    public JsonGetter(HttpClient client, Duration timeout, int maxBodyBytes, ObjectMapper mapper) {
        this.client = client;
        this.timeout = timeout;
        this.maxBodyBytes = maxBodyBytes;
        this.mapper = mapper;
    }

    JsonNode get(Backend backend, URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .header("Accept", "application/json")
                .timeout(timeout)
                .build();
        // sendAsync + get(timeout) bounds the WHOLE exchange, including a body that drips in slowly;
        // HttpRequest.timeout alone only covers the wait for the response headers.
        CompletableFuture<HttpResponse<byte[]>> call = client.sendAsync(request, info -> new BoundedBody(maxBodyBytes));
        HttpResponse<byte[]> response;
        try {
            response = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new BackendFailure(backend, Category.TIMED_OUT, 0);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new BackendFailure(backend, Category.UNREACHABLE, 0);
        } catch (ExecutionException e) {
            throw map(backend, e.getCause());
        }
        int status = response.statusCode();
        if (status >= 400 && status < 500) {
            throw new BackendFailure(backend, Category.REJECTED, status);
        }
        if (status < 200 || status >= 300) {
            throw new BackendFailure(backend, Category.SERVER_ERROR, status);
        }
        try {
            JsonNode node = mapper.readTree(response.body());
            if (node == null || node.isMissingNode()) {
                throw new BackendFailure(backend, Category.MALFORMED, status);
            }
            return node;
        } catch (IOException e) {
            throw new BackendFailure(backend, Category.MALFORMED, status);
        }
    }

    private static BackendFailure map(Backend backend, Throwable cause) {
        Throwable root = cause instanceof CompletionException && cause.getCause() != null ? cause.getCause() : cause;
        if (root instanceof BodyTooLarge) {
            return new BackendFailure(backend, Category.TOO_LARGE, 0);
        }
        if (root instanceof HttpTimeoutException) {
            return new BackendFailure(backend, Category.TIMED_OUT, 0);
        }
        return new BackendFailure(backend, Category.UNREACHABLE, 0);
    }

    private static final class BodyTooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BodyTooLarge() {
            super(null, null, false, false);
        }
    }

    /** Collects the body up to a limit, then cancels the transfer instead of reading the rest. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final int limit;
        private final ByteArrayOutputStream collected = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        BoundedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (collected.size() + buffer.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new BodyTooLarge());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                collected.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(collected.toByteArray());
        }
    }
}
