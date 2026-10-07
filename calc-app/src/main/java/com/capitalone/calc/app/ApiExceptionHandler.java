package com.capitalone.calc.app;

import com.capitalone.calc.spi.CalculateNDIRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to application/problem+json. A calculator bug becomes a 500 on that
 * tenant's request only; nothing about it reaches the caller beyond the problem type.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        HttpHeaders headers = new HttpHeaders();
        if (e instanceof RateLimitedException limited) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()));
        }
        return problem(e.status(), e.type(), e.getMessage(), headers);
    }

    @ExceptionHandler(CalculateNDIRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(CalculateNDIRejectedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, Problems.CALCULATION_REJECTED, e.getMessage(), new HttpHeaders());
    }

    /** statement_timeout fired: the tenant's own time budget, so it is retryable, not a bug. */
    @ExceptionHandler(QueryTimeoutException.class)
    ResponseEntity<ProblemDetail> timedOut(QueryTimeoutException e) {
        log.warn("Statement timed out", e);
        return api(new StatementTimedOutException());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled failure", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, Problems.INTERNAL_ERROR, "Internal error", new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        InvalidInputException invalid = InvalidInputException.from(e, "");
        return cast(problem(HttpStatus.BAD_REQUEST, Problems.INVALID_INPUT, invalid.getMessage(), new HttpHeaders()));
    }

    /** A calculationId that is not a UUID cannot exist. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return cast(api(new NDINotFoundException()));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String detail,
                                                         HttpHeaders headers) {
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problems.of(status, type, detail));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Object> cast(ResponseEntity<ProblemDetail> response) {
        return (ResponseEntity) response;
    }
}
