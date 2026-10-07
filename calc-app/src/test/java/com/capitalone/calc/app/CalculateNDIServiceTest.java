package com.capitalone.calc.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.capitalone.calc.spi.CalculateNDIRejectedException;
import com.capitalone.calc.spi.TenantContext;
import com.capitalone.calc.spi.TenantId;
import com.capitalone.calc.spi.TenantSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** The service against the real tenant modules, without Spring or a database. */
class CalculateNDIServiceTest {
    private static final String USCARD_INPUT =
            "{\"monthlyNetIncome\":\"5200.00\",\"monthlyHousingCost\":\"1500.00\",\"monthlyDebtPayments\":\"650.00\"}";
    private static final String AUTOFINANCE_INPUT =
            "{\"annualGrossIncome\":\"84000.00\",\"monthlyObligations\":\"2100.00\",\"dependents\":2}";

    private final ObjectMapper mapper = strictMapper();
    private final CalculateNDIService service =
            new CalculateNDIService(TenantCalculatorRegistry.discover(), mapper);

    @Test
    void bothWorkedExamples() throws Exception {
        assertThat(service.calculateNDI(ctx("uscard"), json(USCARD_INPUT)))
                .isEqualTo(new Computation("uscard-1", json("{\"ndi\":\"1850.00\"}")));
        assertThat(service.calculateNDI(ctx("autofinance"), json(AUTOFINANCE_INPUT)))
                .isEqualTo(new Computation("autofinance-1", json("{\"ndi\":\"2550.00\"}")));
    }

    @Test
    void eachTenantsBodyIsRejectedOnTheOthersEndpoint() {
        assertThatThrownBy(() -> service.calculateNDI(ctx("autofinance"), json(USCARD_INPUT)))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageStartingWith("Missing or null field 'input.");
        assertThatThrownBy(() -> service.calculateNDI(ctx("uscard"), json(AUTOFINANCE_INPUT)))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageStartingWith("Missing or null field 'input.");
    }

    @Test
    void unknownFieldsAreRejected() {
        assertThatThrownBy(() -> service.calculateNDI(ctx("uscard"), json(
                "{\"monthlyNetIncome\":\"5200.00\",\"monthlyHousingCost\":\"1500.00\",\"monthlyDebtPayments\":\"650.00\","
                        + "\"bonus\":\"1\"}")))
                .isInstanceOf(InvalidInputException.class)
                .hasMessage("Unknown field 'input.bonus'");
    }

    @Test
    void moneyMustBeADecimalString() {
        assertThatThrownBy(() -> service.calculateNDI(ctx("uscard"), json(
                "{\"monthlyNetIncome\":5200.00,\"monthlyHousingCost\":\"1500.00\",\"monthlyDebtPayments\":\"650.00\"}")))
                .isInstanceOf(InvalidInputException.class)
                .hasMessage("Invalid value for field 'input.monthlyNetIncome'");
    }

    @Test
    void missingNullAndFractionalFieldsAreRejected() {
        assertThatThrownBy(() -> service.calculateNDI(ctx("uscard"), json(
                "{\"monthlyNetIncome\":\"5200.00\",\"monthlyHousingCost\":\"1500.00\"}")))
                .isInstanceOf(InvalidInputException.class)
                .hasMessage("Missing or null field 'input.monthlyDebtPayments'");
        assertThatThrownBy(() -> service.calculateNDI(ctx("uscard"), json(
                "{\"monthlyNetIncome\":\"5200.00\",\"monthlyHousingCost\":null,\"monthlyDebtPayments\":\"1\"}")))
                .isInstanceOf(InvalidInputException.class)
                .hasMessage("Missing or null field 'input.monthlyHousingCost'");
        assertThatThrownBy(() -> service.calculateNDI(ctx("autofinance"), json(
                "{\"annualGrossIncome\":\"84000.00\",\"monthlyObligations\":\"2100.00\",\"dependents\":2.5}")))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("input.dependents");
    }

    @Test
    void tenantRuleViolationIsARejectionNotAnInputError() {
        assertThatThrownBy(() -> service.calculateNDI(ctx("uscard"), json(
                "{\"monthlyNetIncome\":\"0\",\"monthlyHousingCost\":\"0\",\"monthlyDebtPayments\":\"0\"}")))
                .isInstanceOf(CalculateNDIRejectedException.class);
    }

    @Test
    void errorDetailsNeverExposeTenantClassNames() {
        assertThatThrownBy(() -> service.calculateNDI(ctx("uscard"), json("{\"monthlyNetIncome\":[1]}")))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("com.capitalone"));
    }

    private static TenantContext ctx(String tenant) {
        return new TenantContext(new TenantId(tenant), "test", new TenantSettings(Map.of()));
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static ObjectMapper strictMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JsonConfig().strictJson().customize(builder);
        return builder.build();
    }
}
