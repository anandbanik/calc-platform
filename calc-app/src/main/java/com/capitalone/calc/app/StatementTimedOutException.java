package com.capitalone.calc.app;

import org.springframework.http.HttpStatus;

class StatementTimedOutException extends ApiException {
    StatementTimedOutException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, Problems.TIMED_OUT, "The query exceeded the tenant's time budget");
    }
}
