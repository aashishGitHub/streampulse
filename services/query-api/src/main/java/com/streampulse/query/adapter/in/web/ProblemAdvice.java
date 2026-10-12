/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.web;

import com.streampulse.query.application.InvalidRequestException;
import com.streampulse.query.application.UnknownMetricException;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;

/** Every error leaves as {@code application/problem+json}, which the OpenAPI contract promises. */
@RestControllerAdvice
public class ProblemAdvice extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ProblemDetail> invalid(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(UnknownMetricException.class)
    ResponseEntity<ProblemDetail> unknownMetric(UnknownMetricException e) {
        return problem(HttpStatus.NOT_FOUND, "Unknown metric", e.getMessage());
    }

    @ExceptionHandler(RejectedExecutionException.class)
    ResponseEntity<ProblemDetail> saturated(RejectedExecutionException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Busy", "Blocking capacity is exhausted; retry shortly.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
