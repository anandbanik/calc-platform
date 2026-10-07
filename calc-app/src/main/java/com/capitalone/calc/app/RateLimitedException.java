package com.capitalone.calc.app;

import org.springframework.http.HttpStatus;

class RateLimitedException extends ApiException {
    private final long retryAfterSeconds;

    RateLimitedException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, Problems.RATE_LIMITED, "Tenant request quota is used up");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
