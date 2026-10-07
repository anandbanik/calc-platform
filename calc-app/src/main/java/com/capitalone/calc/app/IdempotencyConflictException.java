package com.capitalone.calc.app;

import org.springframework.http.HttpStatus;

class IdempotencyConflictException extends ApiException {
    IdempotencyConflictException() {
        super(HttpStatus.CONFLICT, Problems.IDEMPOTENCY_CONFLICT,
                "Idempotency-Key was already used with a different input");
    }
}
