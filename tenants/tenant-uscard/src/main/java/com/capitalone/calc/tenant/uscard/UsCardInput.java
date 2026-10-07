package com.capitalone.calc.tenant.uscard;

import java.math.BigDecimal;

record UsCardInput(BigDecimal monthlyNetIncome, BigDecimal monthlyHousingCost, BigDecimal monthlyDebtPayments) {}
