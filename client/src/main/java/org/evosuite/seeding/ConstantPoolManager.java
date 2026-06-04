/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.seeding;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the constant pools.
 *
 * @author Gordon Fraser
 */
public class ConstantPoolManager {

    private static final Logger logger = LoggerFactory.getLogger(ConstantPoolManager.class);

    private static class SingletonHolder {
        private static final ConstantPoolManager INSTANCE = new ConstantPoolManager();
    }

    private static final int SUT_POOL_INDEX = 0;
    private static final int NON_SUT_POOL_INDEX = 1;
    private static final int DYNAMIC_POOL_INDEX = 2;

    private volatile ConstantPool[] pools;
    private volatile double[] probabilities;
    private static volatile long adaptiveSeededNumericLimit = 0L;
    private static volatile int adaptiveSeededNumericLimitUpdates = 0;

    private ConstantPoolManager() {
        init();
    }

    public static ConstantPoolManager getInstance() {
        return SingletonHolder.INSTANCE;
    }

    static boolean shouldKeepNumericConstant(Number value) {
        return shouldKeepNumericConstant(value, getSeededNumericLimit());
    }

    static boolean shouldKeepNumericConstant(Number value, long limit) {
        if (value == null) {
            return false;
        }

        if (limit <= 0L) {
            return true;
        }

        if (value instanceof Integer || value instanceof Short || value instanceof Byte || value instanceof Long) {
            return isAbsBelowLimit(value.longValue(), limit);
        }

        if (value instanceof Float || value instanceof Double) {
            double d = value.doubleValue();
            return Double.isFinite(d) && d > -limit && d < limit;
        }

        return true;
    }

    private static long getSeededNumericLimit() {
        long configuredLimit = Properties.RESTRICT_POOL ? Properties.MAX_INT : Properties.MAX_SEEDED_CONSTANT;
        long adaptiveLimit = adaptiveSeededNumericLimit;

        if (configuredLimit <= 0L) {
            return adaptiveLimit;
        }
        if (adaptiveLimit <= 0L) {
            return configuredLimit;
        }
        return Math.min(configuredLimit, adaptiveLimit);
    }

    private static boolean isAbsBelowLimit(long value, long limit) {
        return value > -limit && value < limit;
    }

    private void init() {
        adaptiveSeededNumericLimit = 0L;
        adaptiveSeededNumericLimitUpdates = 0;

        if (!Properties.VARIABLE_POOL) {
            pools = new ConstantPool[]{
                    new StaticConstantPool(),
                    new StaticConstantPool(),
                    new DynamicConstantPool()
            };
        } else {
            pools = new ConstantPool[]{
                    new StaticConstantVariableProbabilityPool(),
                    new StaticConstantVariableProbabilityPool(),
                    new DynamicConstantVariableProbabilityPool()
            };
        }

        initDefaultProbabilities();
    }

    private void initDefaultProbabilities() {
        double[] newProbabilities = new double[pools.length];
        // Distribute remaining probability among non-dynamic pools
        double remainingProbability = 1.0 - Properties.DYNAMIC_POOL;
        double p = remainingProbability / (newProbabilities.length - 1);

        for (int i = 0; i < newProbabilities.length; i++) {
            if (i == DYNAMIC_POOL_INDEX) {
                newProbabilities[i] = Properties.DYNAMIC_POOL;
            } else {
                newProbabilities[i] = p;
            }
        }
        normalizeProbabilities(newProbabilities);
        probabilities = newProbabilities;
    }

    private void normalizeProbabilities(double[] probs) {
        double sum = 0.0;
        for (double p : probs) {
            sum += p;
        }
        if (sum > 0) {
            double delta = 1.0 / sum;
            for (int i = 0; i < probs.length; i++) {
                probs[i] *= delta;
            }
        }
    }

    /**
     * Adds a constant to the SUT pool.
     *
     * @param value the constant to add
     */
    public void addSUTConstant(Object value) {
        pools[SUT_POOL_INDEX].add(value);
    }

    /**
     * Adds a constant to the non-SUT pool.
     *
     * @param value the constant to add
     */
    public void addNonSUTConstant(Object value) {
        pools[NON_SUT_POOL_INDEX].add(value);
    }

    /**
     * Adds a constant to the dynamic pool.
     *
     * @param value the constant to add
     */
    public void addDynamicConstant(Object value) {
        pools[DYNAMIC_POOL_INDEX].add(value);
    }

    /**
     * Feeds the dynamic pool only while the execution tracer is enabled —
     * i.e. during test-generation execution, not JUnit-check / coverage reruns
     * where SUT classes may still carry testability instrumentation but the
     * collected constants would be wasted work and create synchronized-queue
     * contention on hot inner loops.
     */
    public static void addDynamicConstantIfTracing(Object value) {
        if (ExecutionTracer.isEnabled()) {
            getInstance().addDynamicConstant(value);
        }
    }

    /**
     * Gets a constant pool based on probabilities.
     *
     * @return the selected constant pool
     */
    public ConstantPool getConstantPool() {
        double p = Randomness.nextDouble();
        double k = 0.0;
        for (int i = 0; i < probabilities.length; i++) {
            k += probabilities[i];
            if (p < k) {
                return pools[i];
            }
        }
        /*
         * This should not happen, but you never know with double computations...
         */
        return pools[SUT_POOL_INDEX];
    }

    /**
     * Gets the dynamic constant pool.
     *
     * @return the dynamic constant pool
     */
    public ConstantPool getDynamicConstantPool() {
        return pools[DYNAMIC_POOL_INDEX];
    }

    /**
     * Returns the static SUT constant pool (constants collected from the SUT's
     * own bytecode). Used by LLM enrichment to seed the model with the SUT's
     * existing vocabulary.
     */
    public ConstantPool getSUTConstantPool() {
        return pools[SUT_POOL_INDEX];
    }

    public synchronized AdaptiveSeedLimitUpdate tightenSeededNumericLimitAfterOom() {
        if (!Properties.ADAPTIVE_SEEDED_CONSTANTS_ON_OOM) {
            return AdaptiveSeedLimitUpdate.disabled();
        }

        long minLimit = Math.max(1L, Properties.ADAPTIVE_SEEDED_CONSTANT_MIN_LIMIT);
        long current = adaptiveSeededNumericLimit;
        long next;
        if (current <= 0L) {
            next = Math.max(minLimit, Properties.ADAPTIVE_SEEDED_CONSTANT_INITIAL_LIMIT);
        } else {
            next = Math.max(minLimit, current / 2L);
        }

        adaptiveSeededNumericLimit = next;
        adaptiveSeededNumericLimitUpdates++;
        int pruned = pruneOversizedNumericConstants(next);

        AdaptiveSeedLimitUpdate update =
                new AdaptiveSeedLimitUpdate(true, adaptiveSeededNumericLimitUpdates, next, pruned);
        logger.warn("OOM adaptive seeding update: {}", update);
        return update;
    }

    public synchronized int pruneOversizedNumericConstants(long maxAbsExclusive) {
        if (maxAbsExclusive <= 0L || pools == null) {
            return 0;
        }
        int removed = 0;
        for (ConstantPool pool : pools) {
            removed += pool.pruneOversizedNumericConstants(maxAbsExclusive);
        }
        return removed;
    }

    public static int sanitizeTestCaseNumericLiterals(TestCase testCase) {
        if (testCase == null) {
            return 0;
        }

        long limit = getSeededNumericLimit();
        if (limit <= 0L) {
            return 0;
        }

        int changed = 0;
        for (Statement statement : testCase) {
            if (!(statement instanceof PrimitiveStatement<?>)) {
                continue;
            }
            PrimitiveStatement<?> primitive = (PrimitiveStatement<?>) statement;
            Object value = primitive.getValue();
            if (!(value instanceof Number)) {
                continue;
            }
            Object clamped = clampToLimit((Number) value, limit);
            if (!value.equals(clamped)) {
                @SuppressWarnings("rawtypes")
                PrimitiveStatement rawPrimitive = primitive;
                rawPrimitive.setValue(clamped);
                changed++;
            }
        }
        return changed;
    }

    private static Object clampToLimit(Number value, long limit) {
        if (limit <= 0L) {
            return value;
        }
        if (value instanceof Integer) {
            long clamped = clampSigned(value.longValue(), Math.min(Integer.MAX_VALUE, limit - 1L));
            return (int) clamped;
        }
        if (value instanceof Long) {
            long clamped = clampSigned(value.longValue(), limit - 1L);
            return clamped;
        }
        if (value instanceof Short) {
            long clamped = clampSigned(value.longValue(), Math.min(Short.MAX_VALUE, limit - 1L));
            return (short) clamped;
        }
        if (value instanceof Byte) {
            long clamped = clampSigned(value.longValue(), Math.min(Byte.MAX_VALUE, limit - 1L));
            return (byte) clamped;
        }
        if (value instanceof Float) {
            float v = value.floatValue();
            if (!Float.isFinite(v)) {
                return 0.0f;
            }
            float bound = (float) (limit - 1L);
            if (v > bound) {
                return bound;
            }
            if (v < -bound) {
                return -bound;
            }
            return v;
        }
        if (value instanceof Double) {
            double v = value.doubleValue();
            if (!Double.isFinite(v)) {
                return 0.0d;
            }
            double bound = (double) (limit - 1L);
            if (v > bound) {
                return bound;
            }
            if (v < -bound) {
                return -bound;
            }
            return v;
        }
        return value;
    }

    private static long clampSigned(long value, long bound) {
        if (bound < 0L) {
            return 0L;
        }
        if (value > bound) {
            return bound;
        }
        if (value < -bound) {
            return -bound;
        }
        return value;
    }

    public static final class AdaptiveSeedLimitUpdate {
        private final boolean enabled;
        private final int activationCount;
        private final long limit;
        private final int prunedConstants;

        private AdaptiveSeedLimitUpdate(boolean enabled, int activationCount,
                                        long limit, int prunedConstants) {
            this.enabled = enabled;
            this.activationCount = activationCount;
            this.limit = limit;
            this.prunedConstants = prunedConstants;
        }

        static AdaptiveSeedLimitUpdate disabled() {
            return new AdaptiveSeedLimitUpdate(false, 0, 0L, 0);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public int getActivationCount() {
            return activationCount;
        }

        public long getLimit() {
            return limit;
        }

        public int getPrunedConstants() {
            return prunedConstants;
        }

        @Override
        public String toString() {
            return "enabled=" + enabled
                    + ", activationCount=" + activationCount
                    + ", limit=" + limit
                    + ", prunedConstants=" + prunedConstants;
        }
    }

    public void reset() {
        init();
    }
}
