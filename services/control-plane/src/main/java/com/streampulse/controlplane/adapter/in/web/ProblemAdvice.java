/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web;

import com.streampulse.controlplane.domain.AlreadyExistsException;
import com.streampulse.controlplane.domain.ConflictException;
import com.streampulse.controlplane.domain.InvalidInputException;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.RuleViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error leaves as application/problem+json (RFC 7807). */
@RestControllerAdvice
public class ProblemAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler({AlreadyExistsException.class, ConflictException.class})
    ProblemDetail conflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(RuleViolationException.class)
    ProblemDetail unprocessable(RuleViolationException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Rule violation", e.getMessage());
    }

    @ExceptionHandler(InvalidInputException.class)
    ProblemDetail invalid(InvalidInputException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid input", e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail staleWrite(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification", "the entity was changed by someone else; retry");
    }

    /** A duplicate that raced past the existence check, caught by the primary key. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        log.warn("integrity violation", e);
        return problem(HttpStatus.CONFLICT, "Conflict", "the change conflicts with existing data");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "unexpected error");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "validation failed");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
