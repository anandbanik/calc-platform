package com.capitalone.calc.app;

import com.capitalone.calc.spi.TenantId;
import java.util.function.Supplier;

/** Runs work inside one tenant's rate limit and concurrency limit. */
public interface TenantGuard {
    <T> T run(TenantId tenant, Supplier<T> work);
}
