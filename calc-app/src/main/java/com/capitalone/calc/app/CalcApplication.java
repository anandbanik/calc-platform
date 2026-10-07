package com.capitalone.calc.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CalcApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalcApplication.class, args);
    }

    @Bean
    TenantCalculatorRegistry tenantCalculatorRegistry() {
        return TenantCalculatorRegistry.discover();
    }
}
