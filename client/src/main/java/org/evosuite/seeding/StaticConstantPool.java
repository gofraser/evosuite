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
import org.evosuite.utils.Randomness;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Static constant pool.
 *
 * @author Gordon Fraser
 */
public class StaticConstantPool implements ConstantPool {

    private static final Logger logger = LoggerFactory.getLogger(StaticConstantPool.class);

    private static final int MAX_STRING_LITERAL_LENGTH = 65535;

    private static class FastPool<T> {
        private final Set<T> set = new LinkedHashSet<>();
        private final java.util.List<T> list = new java.util.ArrayList<>();
        private boolean dirty = false;

        public synchronized void add(T element) {
            if (element == null) {
                return;
            }
            if (set.add(element)) {
                list.add(element);
                dirty = true;
            }
        }

        public synchronized T getRandom() {
            if (list.isEmpty()) {
                return null;
            }
            if (dirty) {
                list.sort(Randomness::deterministicCompare);
                dirty = false;
            }
            return Randomness.choice(list);
        }

        public synchronized int removeIf(Predicate<? super T> predicate) {
            int removed = 0;
            for (T element : new java.util.ArrayList<>(list)) {
                if (predicate.test(element)) {
                    if (set.remove(element)) {
                        removed++;
                    }
                }
            }
            if (removed > 0) {
                list.removeIf(predicate);
                dirty = true;
            }
            return removed;
        }

        public synchronized int size() {
            return list.size();
        }

        public synchronized List<T> snapshot() {
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
    }

    private final FastPool<String> stringPool = new FastPool<>();

    private final FastPool<Type> typePool = new FastPool<>();

    private final FastPool<Integer> intPool = new FastPool<>();

    private final FastPool<Double> doublePool = new FastPool<>();

    private final FastPool<Long> longPool = new FastPool<>();

    private final FastPool<Float> floatPool = new FastPool<>();

    /**
     * Initializes the static constant pool.
     */
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
        return stringPool.getRandom();
    }

    /**
     * Returns a random type.
     *
     * @return a {@link org.objectweb.asm.Type} object.
     */
    @Override
    public Type getRandomType() {
        return typePool.getRandom();
    }

    /**
     * Returns a random int.
     *
     * @return a int.
     */
    @Override
    public int getRandomInt() {
        return intPool.getRandom();
    }

    /**
     * Returns a random float.
     *
     * @return a float.
     */
    @Override
    public float getRandomFloat() {
        return floatPool.getRandom();
    }

    /**
     * Returns a random double.
     *
     * @return a double.
     */
    @Override
    public double getRandomDouble() {
        return doublePool.getRandom();
    }

    /**
     * Returns a random long.
     *
     * @return a long.
     */
    @Override
    public long getRandomLong() {
        return longPool.getRandom();
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
            if (ConstantPoolManager.shouldKeepNumericConstant((Integer) object)) {
                intPool.add((Integer) object);
            }
        } else if (object instanceof Long) {
            if (ConstantPoolManager.shouldKeepNumericConstant((Long) object)) {
                longPool.add((Long) object);
            }
        } else if (object instanceof Float) {
            if (ConstantPoolManager.shouldKeepNumericConstant((Float) object)) {
                floatPool.add((Float) object);
            }
        } else if (object instanceof Double) {
            if (ConstantPoolManager.shouldKeepNumericConstant((Double) object)) {
                doublePool.add((Double) object);
            }
        } else {
            logger.info("Constant of unknown type: {}", object.getClass());
        }
    }

    @Override
    public int pruneOversizedNumericConstants(long maxAbsExclusive) {
        if (maxAbsExclusive <= 0L) {
            return 0;
        }
        int removed = 0;
        removed += intPool.removeIf(value -> !ConstantPoolManager.shouldKeepNumericConstant(value, maxAbsExclusive));
        removed += longPool.removeIf(value -> !ConstantPoolManager.shouldKeepNumericConstant(value, maxAbsExclusive));
        removed += floatPool.removeIf(value -> !ConstantPoolManager.shouldKeepNumericConstant(value, maxAbsExclusive));
        removed += doublePool.removeIf(value -> !ConstantPoolManager.shouldKeepNumericConstant(value, maxAbsExclusive));

        if (intPool.size() == 0) {
            intPool.add(0);
        }
        if (longPool.size() == 0) {
            longPool.add(0L);
        }
        if (floatPool.size() == 0) {
            floatPool.add(0.0f);
        }
        if (doublePool.size() == 0) {
            doublePool.add(0.0);
        }
        return removed;
    }

    @Override
    public Collection<String> getStrings() {
        return stringPool.snapshot();
    }

    @Override
    public Collection<Integer> getInts() {
        return intPool.snapshot();
    }

    @Override
    public Collection<Long> getLongs() {
        return longPool.snapshot();
    }

    @Override
    public Collection<Float> getFloats() {
        return floatPool.snapshot();
    }

    @Override
    public Collection<Double> getDoubles() {
        return doublePool.snapshot();
    }

}
