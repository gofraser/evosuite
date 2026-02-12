/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */

package org.evosuite.seeding;

import org.evosuite.Properties;
import org.evosuite.utils.Randomness;

/**
 * Manages the constant pools.
 *
 * @author Gordon Fraser
 */
public class ConstantPoolManager {

    private static class SingletonHolder {
        private static final ConstantPoolManager INSTANCE = new ConstantPoolManager();
    }

    private static final int SUT_POOL_INDEX = 0;
    private static final int NON_SUT_POOL_INDEX = 1;
    private static final int DYNAMIC_POOL_INDEX = 2;

    private volatile ConstantPool[] pools;
    private volatile double[] probabilities;

    private ConstantPoolManager() {
        init();
    }

    public static ConstantPoolManager getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private void init() {
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
        probabilities = new double[pools.length];
        // Distribute remaining probability among non-dynamic pools
        double remainingProbability = 1.0 - Properties.DYNAMIC_POOL;
        double p = remainingProbability / (probabilities.length - 1);

        for (int i = 0; i < probabilities.length; i++) {
            if (i == DYNAMIC_POOL_INDEX) {
                probabilities[i] = Properties.DYNAMIC_POOL;
            } else {
                probabilities[i] = p;
            }
        }
        normalizeProbabilities();
    }

    private void normalizeProbabilities() {
        double sum = 0.0;
        for (double p : probabilities) {
            sum += p;
        }
        if (sum > 0) {
            double delta = 1.0 / sum;
            for (int i = 0; i < probabilities.length; i++) {
                probabilities[i] *= delta;
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

    public void reset() {
        init();
    }
}
