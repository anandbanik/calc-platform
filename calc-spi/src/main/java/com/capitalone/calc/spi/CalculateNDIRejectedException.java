package com.capitalone.calc.spi;

/** Thrown by a calculator when well-formed input breaks one of the tenant's rules. Maps to 422. */
public class CalculateNDIRejectedException extends RuntimeException {
    public CalculateNDIRejectedException(String message) {
        super(message);
    }
}
