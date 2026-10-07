package com.capitalone.calc.app;

import com.capitalone.calc.spi.TenantCalculator;
import com.capitalone.calc.spi.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** Identical for every tenant: look up, convert, call. The guard runs one level up, around the whole request. */
@Service
public final class CalculateNDIService {
    private final TenantCalculatorRegistry registry;
    private final ObjectMapper mapper;

    public CalculateNDIService(TenantCalculatorRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    public Computation calculateNDI(TenantContext ctx, JsonNode rawInput) {
        return run(registry.forTenant(ctx.tenantId()), ctx, rawInput);
    }

    private <I, O> Computation run(TenantCalculator<I, O> calculator, TenantContext ctx, JsonNode rawInput) {
        I input = toInput(rawInput, calculator.inputType());
        O output = calculator.calculateNDI(ctx, input);
        return new Computation(calculator.version(), mapper.valueToTree(output));
    }

    private <I> I toInput(JsonNode rawInput, Class<I> type) {
        if (rawInput == null || !rawInput.isObject()) {
            throw new InvalidInputException("'input' must be a JSON object");
        }
        try {
            return mapper.convertValue(rawInput, type);
        } catch (IllegalArgumentException e) {
            throw InvalidInputException.from(e, "input");
        }
    }
}
