package com.capitalone.calc.app;

import org.springframework.http.HttpStatus;

/** An error the API reports to the caller with a stable problem type. */
abstract class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String type;

    ApiException(HttpStatus status, String type, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
    }

    HttpStatus status() {
        return status;
    }

    String type() {
        return type;
    }
}
