package com.capitalone.calc.app;

import com.capitalone.calc.spi.TenantId;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** One rate limiter and one bulkhead per tenant, sized from that tenant's limits. Neither waits. */
@Component
class Resilience4jTenantGuard implements TenantGuard {
    private static final Duration REFRESH_PERIOD = Duration.ofSeconds(1);

    private final RateLimiterRegistry limiters = RateLimiterRegistry.ofDefaults();
    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    private final TenantSettingsProvider tenants;

    Resilience4jTenantGuard(TenantSettingsProvider tenants) {
        this.tenants = tenants;
    }

    @Override
    public <T> T run(TenantId tenant, Supplier<T> work) {
        TenantLimits limits = tenants.limits(tenant);
        RateLimiter limiter = limiters.rateLimiter(tenant.value(), () -> RateLimiterConfig.custom()
                .limitForPeriod(limits.requestsPerSecond())
                .limitRefreshPeriod(REFRESH_PERIOD)
                .timeoutDuration(Duration.ZERO)
                .build());
        Bulkhead bulkhead = bulkheads.bulkhead(tenant.value(), () -> BulkheadConfig.custom()
                .maxConcurrentCalls(limits.maxConcurrent())
                .maxWaitDuration(Duration.ZERO)
                .build());
        try {
            return RateLimiter.decorateSupplier(limiter, Bulkhead.decorateSupplier(bulkhead, work)).get();
        } catch (RequestNotPermitted e) {
            throw new RateLimitedException(REFRESH_PERIOD.toSeconds());
        } catch (BulkheadFullException e) {
            throw new TenantBusyException();
        }
    }
}
