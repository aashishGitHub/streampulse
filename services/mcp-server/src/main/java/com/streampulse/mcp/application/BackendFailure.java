/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import com.streampulse.mcp.domain.Backend;

/**
 * A backend could not give a usable answer. The message is built from fixed words only: a backend's own
 * error text is tenant-influenced and must not reach the client.
 */
public final class BackendFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Category {
        UNREACHABLE,
        TIMED_OUT,
        REJECTED,
        SERVER_ERROR,
        MALFORMED,
        TOO_LARGE
    }

    private final Backend backend;
    private final Category category;
    private final int status;

    public BackendFailure(Backend backend, Category category, int status) {
        super(message(backend, category, status));
        this.backend = backend;
        this.category = category;
        this.status = status;
    }

    public Backend backend() {
        return backend;
    }

    public Category category() {
        return category;
    }

    public int status() {
        return status;
    }

    private static String message(Backend backend, Category category, int status) {
        String name = backend.label();
        return switch (category) {
            case UNREACHABLE -> name + " unreachable";
            case TIMED_OUT -> name + " timed out";
            case REJECTED -> name + " rejected the request (status " + status + ")";
            case SERVER_ERROR -> name + " failed (status " + status + ")";
            case MALFORMED -> name + " returned a malformed response";
            case TOO_LARGE -> name + " returned a response that is too large";
        };
    }
}
