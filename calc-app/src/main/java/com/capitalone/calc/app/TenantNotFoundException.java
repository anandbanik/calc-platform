package com.capitalone.calc.app;

import com.capitalone.calc.spi.TenantId;
import org.springframework.http.HttpStatus;

/** The caller's own tenant has no calculator registered. */
class TenantNotFoundException extends ApiException {
    TenantNotFoundException(TenantId id) {
        super(HttpStatus.NOT_FOUND, Problems.TENANT_NOT_FOUND, "No calculator is registered for tenant " + id.value());
    }
}
