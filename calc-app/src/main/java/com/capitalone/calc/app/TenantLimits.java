package com.capitalone.calc.app;

/** Runtime capacity for one tenant: platform defaults, overridden by tenants.<id>.limits.*. */
record TenantLimits(int requestsPerSecond, int maxConcurrent, int statementTimeoutMs) {
    TenantLimits {
        if (requestsPerSecond < 1 || maxConcurrent < 1) {
            throw new IllegalArgumentException("Tenant limits must be positive");
        }
        if (statementTimeoutMs < 1) {
            throw new IllegalArgumentException("Statement timeout must be positive");
        }
    }
}
