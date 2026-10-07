package com.capitalone.calc.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two assumptions behind the repository's per-tenant statement_timeout: that set_config
 * with is_local=true arms it for the transaction, and that Spring surfaces the cancellation
 * as QueryTimeoutException, which is what ApiExceptionHandler turns into a 503.
 */
@SpringBootTest
class StatementTimeoutTest extends PostgresTestSupport {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aStatementOverTheBudgetIsCancelled() {
        assertThatThrownBy(() -> tx.execute(status -> {
            arm("50");
            return jdbc.sql("SELECT pg_sleep(2)").query(String.class).single();
        })).isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void theTimeoutIsLocalToTheTransactionAndDoesNotLeak() {
        tx.execute(status -> {
            arm("50");
            return assertThat(setting()).isEqualTo("50ms");
        });
        // A later transaction on the same pooled connection starts from the server default again.
        tx.execute(status -> assertThat(setting()).isEqualTo("0"));
    }

    private void arm(String millis) {
        jdbc.sql("SELECT set_config('statement_timeout', :timeout, true) AS timeout")
                .param("timeout", millis)
                .query()
                .singleRow();
    }

    private String setting() {
        return jdbc.sql("SELECT current_setting('statement_timeout')").query(String.class).single();
    }
}
