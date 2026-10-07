package com.capitalone.calc.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.capitalone.calc.spi.TenantCalculator;
import com.capitalone.calc.spi.TenantContext;
import com.capitalone.calc.spi.TenantId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantCalculatorRegistryTest {

    @Test
    void discoversEveryTenantModuleOnTheClasspath() {
        TenantCalculatorRegistry registry = TenantCalculatorRegistry.discover();

        assertThat(registry.tenants()).containsExactly("autofinance", "uscard");
        assertThat(registry.forTenant(new TenantId("uscard")).version()).isEqualTo("uscard-1");
        assertThat(registry.forTenant(new TenantId("autofinance")).version()).isEqualTo("autofinance-1");
    }

    @Test
    void unknownTenantIsNotFound() {
        assertThatThrownBy(() -> TenantCalculatorRegistry.discover().forTenant(new TenantId("ghost")))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void refusesTwoCalculatorsForOneTenant() {
        Map<TenantId, TenantCalculator<?, ?>> found = new HashMap<>();
        TenantCalculatorRegistry.register(found, new Fake("uscard"));

        assertThatThrownBy(() -> TenantCalculatorRegistry.register(found, new Fake("uscard")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("uscard");
    }

    private record Fake(String id) implements TenantCalculator<String, String> {
        @Override public TenantId tenantId() { return new TenantId(id); }
        @Override public String version() { return "fake"; }
        @Override public Class<String> inputType() { return String.class; }
        @Override public String calculateNDI(TenantContext ctx, String input) { return input; }
    }
}
