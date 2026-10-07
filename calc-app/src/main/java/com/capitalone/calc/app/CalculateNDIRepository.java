package com.capitalone.calc.app;

import com.capitalone.calc.spi.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every method takes a TenantContext and runs in a transaction opened for that tenant, so
 * row-level security filters the statement even if its WHERE clause is wrong. The same
 * transaction carries that tenant's statement_timeout, so one tenant's slow query cannot
 * hold a pooled connection indefinitely.
 */
@Repository
class CalculateNDIRepository {
    private static final String COLUMNS =
            "tenant_id, calculation_id, calculator_version, input, result, computed_at";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final TenantSettingsProvider tenants;
    private final RowMapper<StoredNDI> rows = this::toStored;

    CalculateNDIRepository(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper mapper,
                           TenantSettingsProvider tenants) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.tenants = tenants;
    }

    StoredNDI save(TenantContext ctx, String idempotencyKey, JsonNode input, Computation computed) {
        return inTenant(ctx, () -> jdbc.sql("""
                INSERT INTO ndi_calculation
                       (tenant_id, calculation_id, calculator_version, idempotency_key, input, result)
                VALUES (:tenant, :id, :version, :key, CAST(:input AS jsonb), CAST(:result AS jsonb))
                RETURNING""" + " " + COLUMNS)
                .param("tenant", ctx.tenantId().value())
                .param("id", UUID.randomUUID())
                .param("version", computed.calculatorVersion())
                .param("key", idempotencyKey)
                .param("input", json(input))
                .param("result", json(computed.result()))
                .query(rows)
                .single());
    }

    Optional<StoredNDI> find(TenantContext ctx, UUID calculationId) {
        return inTenant(ctx, () -> jdbc.sql("SELECT " + COLUMNS + """
                 FROM ndi_calculation
                WHERE tenant_id = :tenant AND calculation_id = :id""")
                .param("tenant", ctx.tenantId().value())
                .param("id", calculationId)
                .query(rows)
                .optional());
    }

    Optional<StoredNDI> findByIdempotencyKey(TenantContext ctx, String idempotencyKey) {
        return inTenant(ctx, () -> jdbc.sql("SELECT " + COLUMNS + """
                 FROM ndi_calculation
                WHERE tenant_id = :tenant AND idempotency_key = :key""")
                .param("tenant", ctx.tenantId().value())
                .param("key", idempotencyKey)
                .query(rows)
                .optional());
    }

    /** The third argument true makes each setting local to the transaction, so neither can leak on a pooled connection. */
    private <T> T inTenant(TenantContext ctx, Supplier<T> work) {
        String timeoutMs = Integer.toString(tenants.limits(ctx.tenantId()).statementTimeoutMs());
        return tx.execute(status -> {
            jdbc.sql("""
                    SELECT set_config('app.tenant_id', :tenant, true) AS tenant,
                           set_config('statement_timeout', :timeout, true) AS timeout""")
                    .param("tenant", ctx.tenantId().value())
                    .param("timeout", timeoutMs)
                    .query()
                    .singleRow();
            return work.get();
        });
    }

    private StoredNDI toStored(ResultSet rs, int rowNum) throws SQLException {
        return new StoredNDI(
                rs.getString("tenant_id"),
                rs.getObject("calculation_id", UUID.class),
                rs.getString("calculator_version"),
                tree(rs.getString("input")),
                tree(rs.getString("result")),
                rs.getObject("computed_at", OffsetDateTime.class).toInstant());
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode tree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
