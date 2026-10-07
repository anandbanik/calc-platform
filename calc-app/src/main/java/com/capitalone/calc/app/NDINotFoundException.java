package com.capitalone.calc.app;

import org.springframework.http.HttpStatus;

class NDINotFoundException extends ApiException {
    NDINotFoundException() {
        super(HttpStatus.NOT_FOUND, Problems.CALCULATION_NOT_FOUND, "No such calculation for this tenant");
    }
}
