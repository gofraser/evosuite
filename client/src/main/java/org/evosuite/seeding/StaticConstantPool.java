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
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Static constant pool.
 *
 * @author Gordon Fraser
 */
public class StaticConstantPool implements ConstantPool {

    private static final Logger logger = LoggerFactory.getLogger(StaticConstantPool.class);

    private static final int MAX_STRING_LITERAL_LENGTH = 65535;

    private final Set<String> stringPool = Collections.synchronizedSet(new LinkedHashSet<>());

    private final Set<Type> typePool = Collections.synchronizedSet(new LinkedHashSet<>());

    private final Set<Integer> intPool = Collections.synchronizedSet(new LinkedHashSet<>());

    private final Set<Double> doublePool = Collections.synchronizedSet(new LinkedHashSet<>());

    private final Set<Long> longPool = Collections.synchronizedSet(new LinkedHashSet<>());

    private final Set<Float> floatPool = Collections.synchronizedSet(new LinkedHashSet<>());

    public StaticConstantPool() {
        /*
         * all pools HAVE to be non-empty
         */

        stringPool.add("");

        if (Properties.TARGET_CLASS != null && !Properties.TARGET_CLASS.isEmpty()) {
            typePool.add(Type.getObjectType(Properties.TARGET_CLASS));
        } else {
            typePool.add(Type.getType(Object.class));
        }

        intPool.add(0);
        intPool.add(1);
        intPool.add(-1);

        longPool.add(0L);
        longPool.add(1L);
        longPool.add(-1L);

        floatPool.add(0.0f);
        floatPool.add(1.0f);
        floatPool.add(-1.0f);

        doublePool.add(0.0);
        doublePool.add(1.0);
        doublePool.add(-1.0);
    }

    /**
     * Returns a random string.
     *
     * @return a {@link java.lang.String} object.
     */
    @Override
    public String getRandomString() {
        return Randomness.choice(stringPool);
    }

    @Override
    public Type getRandomType() {
        return Randomness.choice(typePool);
    }

    /**
     * Returns a random int.
     *
     * @return a int.
     */
    @Override
    public int getRandomInt() {
        return Randomness.choice(intPool);
    }

    /**
     * Returns a random float.
     *
     * @return a float.
     */
    @Override
    public float getRandomFloat() {
        return Randomness.choice(floatPool);
    }

    /**
     * Returns a random double.
     *
     * @return a double.
     */
    @Override
    public double getRandomDouble() {
        return Randomness.choice(doublePool);
    }

    /**
     * Returns a random long.
     *
     * @return a long.
     */
    @Override
    public long getRandomLong() {
        return Randomness.choice(longPool);
    }

    /**
     * Adds an object to the pool.
     *
     * @param object a {@link java.lang.Object} object.
     */
    @Override
    public void add(Object object) {
        // We don't add null because this is explicitly handled in the TestFactory
        if (object == null) {
            return;
        }

        if (object instanceof String) {
            String string = (String) object;
            if (string.length() > Properties.MAX_STRING) {
                return;
            }
            // String literals are constrained to 65535 bytes
            // as they are stored in the constant pool
            if (string.length() > MAX_STRING_LITERAL_LENGTH) {
                return;
            }
            stringPool.add(string);
        } else if (object instanceof Type) {
            while (((Type) object).getSort() == Type.ARRAY) {
                object = ((Type) object).getElementType();
            }
            typePool.add((Type) object);
        } else if (object instanceof Integer) {
            if (Properties.RESTRICT_POOL) {
                int val = (Integer) object;
                if (Math.abs(val) < Properties.MAX_INT) {
                    intPool.add((Integer) object);
                }
            } else {
                intPool.add((Integer) object);
            }
        } else if (object instanceof Long) {
            if (Properties.RESTRICT_POOL) {
                long val = (Long) object;
                if (Math.abs(val) < Properties.MAX_INT) {
                    longPool.add((Long) object);
                }
            } else {
                longPool.add((Long) object);
            }
        } else if (object instanceof Float) {
            if (Properties.RESTRICT_POOL) {
                float val = (Float) object;
                if (Math.abs(val) < Properties.MAX_INT) {
                    floatPool.add((Float) object);
                }
            } else {
                floatPool.add((Float) object);
            }
        } else if (object instanceof Double) {
            if (Properties.RESTRICT_POOL) {
                double val = (Double) object;
                if (Math.abs(val) < Properties.MAX_INT) {
                    doublePool.add((Double) object);
                }
            } else {
                doublePool.add((Double) object);
            }
        } else {
            logger.info("Constant of unknown type: {}", object.getClass());
        }
    }

}
