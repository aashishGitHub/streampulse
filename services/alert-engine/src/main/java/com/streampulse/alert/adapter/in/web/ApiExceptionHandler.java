/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 problem documents. The content type is set explicitly: the stream operation produces
 * {@code text/event-stream}, and a client asking for that would otherwise get 406 for its own 400.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParameter(MissingServletRequestParameterException e) {
        return badRequest("missing-parameter", "Missing query parameter", "'" + e.getParameterName() + "' is required");
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ProblemDetail> invalid(InvalidRequestException e) {
        return badRequest("invalid-parameter", "Invalid query parameter", e.getMessage());
    }

    private static ResponseEntity<ProblemDetail> badRequest(String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create("urn:streampulse:problem:" + type));
        problem.setTitle(title);
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
