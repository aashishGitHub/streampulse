/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.rest;

import com.streampulse.ingest.domain.BackpressureException;
import com.streampulse.ingest.domain.QuotaExceededException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One error contract for the whole service, as RFC 7807 {@code application/problem+json}.
 *
 * <h2>Why centralise this</h2>
 *
 * Without it, every controller invents its own error shape and clients end up parsing three
 * different JSON structures from one service. {@code @RestControllerAdvice} is
 * cross-cutting error handling done once — the same AOP-adjacent mechanism as
 * {@code @Transactional}, applied to exceptions.
 *
 * <h2>Why 429 and 503 are different</h2>
 *
 * Both mean "not now", but they mean different things and a good client reacts differently:
 *
 * <ul>
 *   <li><b>429</b> — you exceeded <em>your</em> quota. Your problem; slow down. Retrying
 *       against another instance will not help, because the quota is global.
 *   <li><b>503</b> — <em>we</em> are saturated. Our problem; another instance may well cope,
 *       so a retry is reasonable.
 * </ul>
 *
 * Collapsing them into one status throws away information the client needs.
 */
@RestControllerAdvice
class IngestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IngestExceptionHandler.class);

    private static final String PROBLEM_BASE = "https://streampulse.dev/problems/";

    /** Backpressure: we have no capacity. Not an error — the system refusing work correctly. */
    @ExceptionHandler(BackpressureException.class)
    ResponseEntity<ProblemDetail> handleBackpressure(BackpressureException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Ingest is at capacity");
        problem.setType(URI.create(PROBLEM_BASE + "ingest-saturated"));
        problem.setTitle("Ingest saturated");

        long seconds = Math.max(1, e.retryAfter().toSeconds());

        // Retry-After tells a well-behaved client when to come back. The client MUST add
        // jitter: without it every rejected client returns at the same instant and the
        // second spike is worse than the first. Documented in the OpenAPI contract.
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));

        // DEBUG, not ERROR. This is designed behaviour under load; logging it as an error
        // would page someone for the system working as intended.
        log.debug("Rejected for capacity, retry after {}s", seconds);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .headers(headers)
                .body(problem);
    }

    /** Quota exhausted: the tenant's own limit, not our capacity. */
    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ProblemDetail> handleQuotaExceeded(QuotaExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Tenant quota exceeded");
        problem.setType(URI.create(PROBLEM_BASE + "quota-exceeded"));
        problem.setTitle("Quota exceeded");

        long seconds = Math.max(1, e.retryAfter().toSeconds());
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));

        // DEBUG: a throttled tenant is the system working, not failing.
        log.debug("Throttled: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(problem);
    }

    /** Bean Validation failure: name the offending field, so the client can fix it. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("validation failed");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, details);
        problem.setType(URI.create(PROBLEM_BASE + "validation-failed"));
        problem.setTitle("Validation failed");

        return ResponseEntity.badRequest().body(problem);
    }

    /** A malformed identifier — TenantId or EventId rejected the value at the boundary. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setType(URI.create(PROBLEM_BASE + "invalid-request"));
        problem.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(problem);
    }

    /** Anything unexpected. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> handleIllegalState(IllegalStateException e) {
        // ERROR here is right: this one IS unexpected and someone should look at it.
        log.error("Ingest failed", e);

        // Deliberately generic detail. The exception message may name internal hosts or
        // topics, and an error response is attacker-visible. The traceId is how support
        // connects this response to the full stack trace in the logs.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be processed");
        problem.setType(URI.create(PROBLEM_BASE + "internal-error"));
        problem.setTitle("Internal error");
        return ResponseEntity.internalServerError().body(problem);
    }
}
