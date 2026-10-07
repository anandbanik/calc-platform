package com.capitalone.calc.app;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres 16 container for every test class, initialized with the production schema.
 * The app connects as calc_app, not as the container's superuser, so row-level security applies.
 */
abstract class PostgresTestSupport {
    static final String APP_USER = "calc_app";
    static final String APP_PASSWORD = "calc_app";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("db/schema.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }
}
