package com.capitalone.calc.app;

import org.springframework.http.HttpStatus;

/** The token's tenant differs from the path tenant. Same answer whether or not the path tenant exists. */
class TenantMismatchException extends ApiException {
    TenantMismatchException() {
        super(HttpStatus.FORBIDDEN, Problems.TENANT_MISMATCH, "Token is not valid for this tenant");
    }
}
