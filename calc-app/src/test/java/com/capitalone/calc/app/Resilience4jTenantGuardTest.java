package com.capitalone.calc.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.capitalone.calc.spi.TenantId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class Resilience4jTenantGuardTest {
    private static final TenantId NOISY = new TenantId("noisy");
    private static final TenantId QUIET = new TenantId("quiet");

    private final Resilience4jTenantGuard guard = new Resilience4jTenantGuard(new TenantSettingsProvider(
            new MockEnvironment()
                    .withProperty("platform.limits.requests-per-second", "100")
                    .withProperty("platform.limits.max-concurrent", "10")
                    .withProperty("tenants.noisy.limits.requests-per-second", "2")
                    .withProperty("tenants.noisy.limits.max-concurrent", "1")));

    @Test
    void oneTenantUsingUpItsQuotaDoesNotLimitAnother() {
        guard.run(NOISY, () -> "ok");
        guard.run(NOISY, () -> "ok");

        assertThatThrownBy(() -> guard.run(NOISY, () -> "ok"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(1));
        for (int i = 0; i < 20; i++) {
            assertThat(guard.run(QUIET, () -> "ok")).isEqualTo("ok");
        }
    }

    @Test
    void aSlowTenantFillsOnlyItsOwnBulkhead() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> guard.run(NOISY, () -> {
            started.countDown();
            await(release);
            return "slow";
        }));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> guard.run(NOISY, () -> "ok")).isInstanceOf(TenantBusyException.class);
        assertThat(guard.run(QUIET, () -> "ok")).isEqualTo("ok");

        release.countDown();
        assertThat(slow.get(5, TimeUnit.SECONDS)).isEqualTo("slow");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
