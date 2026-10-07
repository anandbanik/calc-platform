package com.capitalone.calc.tenant.uscard;

import com.capitalone.calc.spi.CalculateNDIRejectedException;
import com.capitalone.calc.spi.TenantCalculator;
import com.capitalone.calc.spi.TenantContext;
import com.capitalone.calc.spi.TenantId;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** US Card: monthly net income less housing, debt payments and a living allowance. */
public final class UsCardCalculator implements TenantCalculator<UsCardInput, UsCardResult> {
    private static final TenantId ID = new TenantId("uscard");

    @Override public TenantId tenantId() { return ID; }
    @Override public String version() { return "uscard-1"; }
    @Override public Class<UsCardInput> inputType() { return UsCardInput.class; }

    @Override
    public UsCardResult calculateNDI(TenantContext ctx, UsCardInput input) {
        if (input.monthlyNetIncome().signum() <= 0) {
            throw new CalculateNDIRejectedException("monthlyNetIncome must be positive");
        }
        BigDecimal livingAllowance = ctx.settings().decimal("living-allowance", "1200.00");

        BigDecimal ndi = input.monthlyNetIncome()
                .subtract(input.monthlyHousingCost())
                .subtract(input.monthlyDebtPayments())
                .subtract(livingAllowance)
                .setScale(2, RoundingMode.HALF_EVEN);
        return new UsCardResult(ndi);
    }
}
