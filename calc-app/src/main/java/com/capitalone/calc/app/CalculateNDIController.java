package com.capitalone.calc.app;

import com.capitalone.calc.spi.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every handler body runs inside the tenant's guard, so a tenant over its quota is turned away
 * before it reaches the database. Nothing here may touch the repository outside guard.run.
 */
@RestController
@RequestMapping("/v1/tenants/{tenantId}/calculate-ndi")
class CalculateNDIController {
    private static final Logger log = LoggerFactory.getLogger(CalculateNDIController.class);

    private final CalculateNDIService service;
    private final CalculateNDIRepository repository;
    private final TenantGuard guard;

    CalculateNDIController(CalculateNDIService service, CalculateNDIRepository repository, TenantGuard guard) {
        this.service = service;
        this.repository = repository;
        this.guard = guard;
    }

    @PostMapping
    CalculateNDIResponse calculateNDI(TenantContext ctx,
                                      @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                      @RequestBody CalculateNDIRequest body) {
        return guard.run(ctx.tenantId(), () -> calculate(ctx, key, body));
    }

    private CalculateNDIResponse calculate(TenantContext ctx, String key, CalculateNDIRequest body) {
        if (key != null) {
            Optional<StoredNDI> previous = repository.findByIdempotencyKey(ctx, key);
            if (previous.isPresent()) {
                return replay(previous.get(), body.input());
            }
        }
        Computation computed = service.calculateNDI(ctx, body.input());
        try {
            StoredNDI stored = repository.save(ctx, key, body.input(), computed);
            log.info("Calculated NDI calculationId={} version={}", stored.calculationId(), stored.calculatorVersion());
            return CalculateNDIResponse.of(stored);
        } catch (DuplicateKeyException e) {
            // A concurrent request with the same key stored its result first.
            return replay(repository.findByIdempotencyKey(ctx, key).orElseThrow(() -> e), body.input());
        }
    }

    @GetMapping("/{calculationId}")
    CalculateNDIResponse get(TenantContext ctx, @PathVariable UUID calculationId) {
        return guard.run(ctx.tenantId(), () -> repository.find(ctx, calculationId)
                .map(CalculateNDIResponse::of)
                .orElseThrow(NDINotFoundException::new));
    }

    private static CalculateNDIResponse replay(StoredNDI previous, JsonNode input) {
        if (!previous.input().equals(input)) {
            throw new IdempotencyConflictException();
        }
        return CalculateNDIResponse.of(previous);
    }
}
