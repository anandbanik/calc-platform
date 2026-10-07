package com.capitalone.calc.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/** Stable RFC 9457 problem types, one per row of the API's error table. */
final class Problems {
    static final String INVALID_INPUT = "invalid-input";
    static final String UNAUTHENTICATED = "unauthenticated";
    static final String TENANT_MISMATCH = "tenant-mismatch";
    static final String TENANT_NOT_FOUND = "tenant-not-found";
    static final String CALCULATION_NOT_FOUND = "calculation-not-found";
    static final String IDEMPOTENCY_CONFLICT = "idempotency-conflict";
    static final String CALCULATION_REJECTED = "calculation-rejected";
    static final String PAYLOAD_TOO_LARGE = "payload-too-large";
    static final String RATE_LIMITED = "rate-limited";
    static final String TENANT_BUSY = "tenant-busy";
    static final String TIMED_OUT = "timed-out";
    static final String INTERNAL_ERROR = "internal-error";

    private static final String TYPE_PREFIX = "urn:calc:problem:";

    private Problems() {}

    static ProblemDetail of(HttpStatus status, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + type));
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    /** For the filters, which run outside the DispatcherServlet and so never reach ApiExceptionHandler. */
    static void write(HttpServletResponse response, ObjectMapper mapper, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
