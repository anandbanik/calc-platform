package com.capitalone.calc.app;

import org.springframework.http.HttpStatus;

class TenantBusyException extends ApiException {
    TenantBusyException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, Problems.TENANT_BUSY, "Tenant concurrency limit is reached");
    }
}
