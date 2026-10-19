/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in.web;

import com.streampulse.feed.application.WatchUnavailableException;
import com.streampulse.feed.domain.InvalidFeedRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves as {@code application/problem+json}. The base class already renders
 * Spring's own 4xx (bad body, missing parameter, failed validation) in that shape.
 */
@RestControllerAdvice
public class ProblemAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

    @ExceptionHandler(InvalidFeedRequestException.class)
    ResponseEntity<ProblemDetail> invalid(InvalidFeedRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(WatchUnavailableException.class)
    ResponseEntity<ProblemDetail> notRecorded(WatchUnavailableException e) {
        log.warn("watch not recorded: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Not recorded", "The report was not accepted; retry it.");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> storeDown(DataAccessException e) {
        log.warn("store unavailable: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", "A backing store is unavailable; retry shortly.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
