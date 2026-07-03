package com.examples.with.different.packagename.objectconstruction.legitimization;

/**
 * Fixture for the EvoObj legitimizer. A synthesized template often throws before
 * reaching the target-method call; {@code risky} models such a pre-target failure that the
 * legitimizer must fix by mutating a preceding primitive "slot" (here: making {@code divisor}
 * non-zero) so that execution reaches {@code targetM}.
 */
public class LegitimizationExample {

    /** Throws {@link ArithmeticException} when {@code divisor == 0}. */
    public int risky(int divisor) {
        return 100 / divisor;
    }

    /** The target method: it must be reached for the template to be legitimate. */
    public boolean targetM(int value) {
        return value > 0;
    }
}
