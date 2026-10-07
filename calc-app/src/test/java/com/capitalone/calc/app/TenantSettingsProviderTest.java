package com.capitalone.calc.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.capitalone.calc.spi.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TenantSettingsProviderTest {
    private final TenantSettingsProvider provider = new TenantSettingsProvider(new MockEnvironment()
            .withProperty("platform.limits.requests-per-second", "40")
            .withProperty("platform.limits.max-concurrent", "8")
            .withProperty("tenants.uscard.living-allowance", "1200.00")
            .withProperty("tenants.uscard.limits.requests-per-second", "5")
            .withProperty("tenants.uscard.limits.statement-timeout-ms", "500")
            .withProperty("tenants.autofinance.effective-tax-rate", "0.25"));

    @Test
    void calculatorSeesOnlyItsOwnSettingsWithoutLimits() {
        assertThat(provider.forTenant(new TenantId("uscard")).values())
                .containsExactlyEntriesOf(java.util.Map.of("living-allowance", "1200.00"));
        assertThat(provider.forTenant(new TenantId("autofinance")).values())
                .containsOnlyKeys("effective-tax-rate");
        assertThat(provider.forTenant(new TenantId("ghost")).values()).isEmpty();
    }

    @Test
    void limitsOverridePlatformDefaultsPerTenant() {
        assertThat(provider.limits(new TenantId("uscard"))).isEqualTo(new TenantLimits(5, 8, 500));
        assertThat(provider.limits(new TenantId("autofinance"))).isEqualTo(new TenantLimits(40, 8, 2000));
    }
}
