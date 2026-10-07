package com.capitalone.calc.tenant.autofinance;

import java.math.BigDecimal;

record AutoFinanceInput(BigDecimal annualGrossIncome, BigDecimal monthlyObligations, int dependents) {}
