package com.capitalone.calc.tenant.autofinance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.capitalone.calc.spi.CalculateNDIRejectedException;
import com.capitalone.calc.spi.TenantContext;
import com.capitalone.calc.spi.TenantId;
import com.capitalone.calc.spi.TenantSettings;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Golden cases: a change to any expected value here is a change to Auto Finance's formula. */
class AutoFinanceCalculatorTest {
    private final AutoFinanceCalculator calculator = new AutoFinanceCalculator();

    private static TenantContext ctx(Map<String, String> settings) {
        return new TenantContext(new TenantId("autofinance"), "test", new TenantSettings(settings));
    }

    @ParameterizedTest(name = "{0}/yr, {1} obligations, {2} dependents = {3}")
    @CsvSource({
            "84000.00, 2100.00, 2, 2550.00",   // the worked example in the design
            "84000.00, 2100.00, 0, 3150.00",
            "10000.00,  900.00, 1, -575.00",   // negative NDI is a valid result
            "10000.01,    0.00, 0,  625.00",   // 625.000625 rounds down
    })
    void goldenCases(String annual, String obligations, int dependents, String expected) {
        AutoFinanceResult result = calculator.calculateNDI(ctx(Map.of()),
                new AutoFinanceInput(new BigDecimal(annual), new BigDecimal(obligations), dependents));
        assertEquals(new BigDecimal(expected), result.ndi());
    }

    @Test
    void ratesComeFromTenantSettings() {
        TenantContext ctx = ctx(Map.of("effective-tax-rate", "0.30", "per-dependent-allowance", "250.00"));
        AutoFinanceResult result = calculator.calculateNDI(ctx,
                new AutoFinanceInput(new BigDecimal("84000.00"), new BigDecimal("2100.00"), 2));
        // 7000 * 0.70 - 2100 - 500
        assertEquals(new BigDecimal("2300.00"), result.ndi());
    }

    @Test
    void rejectsNonPositiveIncome() {
        assertThrows(CalculateNDIRejectedException.class, () -> calculator.calculateNDI(ctx(Map.of()),
                new AutoFinanceInput(new BigDecimal("-1"), BigDecimal.ZERO, 0)));
    }

    @Test
    void rejectsNegativeDependents() {
        assertThrows(CalculateNDIRejectedException.class, () -> calculator.calculateNDI(ctx(Map.of()),
                new AutoFinanceInput(new BigDecimal("84000.00"), BigDecimal.ZERO, -1)));
    }
}
