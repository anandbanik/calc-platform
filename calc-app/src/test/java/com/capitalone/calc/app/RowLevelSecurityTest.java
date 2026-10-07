package com.capitalone.calc.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The schema's policy, checked directly as calc_app with no application code in the way. */
class RowLevelSecurityTest extends PostgresTestSupport {
    private static final String TENANT_A = "rls-a";
    private static final String TENANT_B = "rls-b";

    @BeforeAll
    static void insertRowForTenantA() throws SQLException {
        try (Connection c = connect()) {
            openTransactionFor(c, TENANT_A);
            insert(c, TENANT_A);
            c.commit();
        }
    }

    @Test
    void anotherTenantCannotReadOrCountTheRow() throws SQLException {
        try (Connection c = connect()) {
            openTransactionFor(c, TENANT_B);
            assertThat(count(c, "SELECT count(*) FROM ndi_calculation")).isZero();
            assertThat(count(c, "SELECT count(*) FROM ndi_calculation WHERE tenant_id = '" + TENANT_A + "'")).isZero();
        }
    }

    @Test
    void owningTenantCanReadTheRow() throws SQLException {
        try (Connection c = connect()) {
            openTransactionFor(c, TENANT_A);
            assertThat(count(c, "SELECT count(*) FROM ndi_calculation WHERE tenant_id = '" + TENANT_A + "'")).isEqualTo(1);
        }
    }

    @Test
    void sessionWithNoTenantSeesNothing() throws SQLException {
        try (Connection c = connect()) {
            assertThat(count(c, "SELECT count(*) FROM ndi_calculation")).isZero();
        }
    }

    @Test
    void aTenantCannotWriteARowLabelledAsAnother() throws SQLException {
        try (Connection c = connect()) {
            openTransactionFor(c, TENANT_B);
            assertThatThrownBy(() -> insert(c, TENANT_A))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    void resultsAreAppendOnly() throws SQLException {
        try (Connection c = connect()) {
            openTransactionFor(c, TENANT_A);
            assertThatThrownBy(() -> c.createStatement().executeUpdate("DELETE FROM ndi_calculation"))
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void tenantSettingDoesNotOutliveTheTransaction() throws SQLException {
        try (Connection c = connect()) {
            openTransactionFor(c, TENANT_A);
            c.commit();
            assertThat(count(c, "SELECT count(*) FROM ndi_calculation")).isZero();
        }
    }

    private static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        c.setAutoCommit(false);
        return c;
    }

    private static void openTransactionFor(Connection c, String tenant) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            s.setString(1, tenant);
            s.executeQuery().close();
        }
    }

    private static void insert(Connection c, String tenant) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO ndi_calculation (tenant_id, calculation_id, calculator_version, input, result)
                VALUES (?, ?, 'test-1', '{}'::jsonb, '{}'::jsonb)""")) {
            s.setString(1, tenant);
            s.setObject(2, UUID.randomUUID());
            s.executeUpdate();
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
