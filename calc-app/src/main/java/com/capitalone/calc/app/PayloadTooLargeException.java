package com.capitalone.calc.app;

import org.springframework.http.HttpStatus;

class PayloadTooLargeException extends ApiException {
    PayloadTooLargeException(long maxBytes) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, Problems.PAYLOAD_TOO_LARGE,
                "Request body exceeds the " + maxBytes + " byte limit");
    }
}
