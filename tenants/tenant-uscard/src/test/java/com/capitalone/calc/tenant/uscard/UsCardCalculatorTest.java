package com.capitalone.calc.tenant.uscard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.capitalone.calc.spi.CalculateNDIRejectedException;
import com.capitalone.calc.spi.TenantContext;
import com.capitalone.calc.spi.TenantId;
import com.capitalone.calc.spi.TenantSettings;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Golden cases: a change to any expected value here is a change to US Card's formula. */
class UsCardCalculatorTest {
    private final UsCardCalculator calculator = new UsCardCalculator();

    private static TenantContext ctx(Map<String, String> settings) {
        return new TenantContext(new TenantId("uscard"), "test", new TenantSettings(settings));
    }

    @ParameterizedTest(name = "{0} - {1} - {2} = {3}")
    @CsvSource({
            "5200.00, 1500.00,  650.00, 1850.00",   // the worked example in the design
            "3000.00, 1200.00,  900.00, -300.00",   // negative NDI is a valid result
            "4000.005,   0.00,    0.00, 2800.00",   // HALF_EVEN rounding
            "4000.015,   0.00,    0.00, 2800.02",
    })
    void goldenCases(String income, String housing, String debt, String expected) {
        UsCardResult result = calculator.calculateNDI(ctx(Map.of()),
                new UsCardInput(new BigDecimal(income), new BigDecimal(housing), new BigDecimal(debt)));
        assertEquals(new BigDecimal(expected), result.ndi());
    }

    @Test
    void livingAllowanceComesFromTenantSettings() {
        UsCardResult result = calculator.calculateNDI(ctx(Map.of("living-allowance", "1000.00")),
                new UsCardInput(new BigDecimal("5200.00"), new BigDecimal("1500.00"), new BigDecimal("650.00")));
        assertEquals(new BigDecimal("2050.00"), result.ndi());
    }

    @Test
    void rejectsNonPositiveIncome() {
        assertThrows(CalculateNDIRejectedException.class, () -> calculator.calculateNDI(ctx(Map.of()),
                new UsCardInput(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
    }
}
