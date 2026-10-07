package com.capitalone.calc.tenant.autofinance;

import com.capitalone.calc.spi.CalculateNDIRejectedException;
import com.capitalone.calc.spi.TenantCalculator;
import com.capitalone.calc.spi.TenantContext;
import com.capitalone.calc.spi.TenantId;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Auto Finance: annual gross income after tax, less obligations and a per-dependent allowance. */
public final class AutoFinanceCalculator implements TenantCalculator<AutoFinanceInput, AutoFinanceResult> {
    private static final TenantId ID = new TenantId("autofinance");
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    @Override public TenantId tenantId() { return ID; }
    @Override public String version() { return "autofinance-1"; }
    @Override public Class<AutoFinanceInput> inputType() { return AutoFinanceInput.class; }

    @Override
    public AutoFinanceResult calculateNDI(TenantContext ctx, AutoFinanceInput input) {
        if (input.annualGrossIncome().signum() <= 0) {
            throw new CalculateNDIRejectedException("annualGrossIncome must be positive");
        }
        if (input.dependents() < 0) {
            throw new CalculateNDIRejectedException("dependents must not be negative");
        }
        BigDecimal taxRate = ctx.settings().decimal("effective-tax-rate", "0.25");
        BigDecimal perDependent = ctx.settings().decimal("per-dependent-allowance", "300.00");

        BigDecimal monthlyGross = input.annualGrossIncome().divide(MONTHS_PER_YEAR, 10, RoundingMode.HALF_EVEN);
        BigDecimal monthlyNet = monthlyGross.multiply(BigDecimal.ONE.subtract(taxRate));
        BigDecimal ndi = monthlyNet
                .subtract(input.monthlyObligations())
                .subtract(perDependent.multiply(BigDecimal.valueOf(input.dependents())));
        return new AutoFinanceResult(ndi.setScale(2, RoundingMode.HALF_EVEN));
    }
}
