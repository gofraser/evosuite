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
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Constant pool with one {@link ConstantBag} per constant type. What differs between the static
 * and the dynamic pools, and between the uniform and the frequency-based ones, is the bag that
 * is plugged in (selection and eviction) and two flags for the seeding and the type handling.
 *
 * @author Gordon Fraser
 */
public class TypedConstantPool implements ConstantPool {

    private static final Logger logger = LoggerFactory.getLogger(TypedConstantPool.class);

    private static final int MAX_STRING_LITERAL_LENGTH = 65535;

    private final String name;

    private final boolean flattenArrayTypes;

    private final ConstantBag<String> stringPool;

    private final ConstantBag<Type> typePool;

    private final ConstantBag<Integer> intPool;

    private final ConstantBag<Double> doublePool;

    private final ConstantBag<Long> longPool;

    private final ConstantBag<Float> floatPool;

    /**
     * Initializes the pool; all bags HAVE to be non-empty.
     *
     * @param name              the name shown by {@link #toString()}
     * @param bags              creates the bag for one constant type
     * @param seedPlusMinusOne  whether the numeric bags start with 1 and -1 besides 0
     * @param flattenArrayTypes whether an array type is added as its element type
     */
    TypedConstantPool(String name, Supplier<ConstantBag<?>> bags, boolean seedPlusMinusOne,
                      boolean flattenArrayTypes) {
        this.name = name;
        this.flattenArrayTypes = flattenArrayTypes;
        stringPool = newBag(bags);
        typePool = newBag(bags);
        intPool = newBag(bags);
        doublePool = newBag(bags);
        longPool = newBag(bags);
        floatPool = newBag(bags);

        stringPool.add("");

        if (Properties.TARGET_CLASS != null && !Properties.TARGET_CLASS.isEmpty()) {
            typePool.add(Type.getObjectType(Properties.TARGET_CLASS));
        } else {
            typePool.add(Type.getType(Object.class));
        }

        intPool.add(0);
        longPool.add(0L);
        floatPool.add(0.0f);
        doublePool.add(0.0);
        if (seedPlusMinusOne) {
            intPool.add(1);
            intPool.add(-1);
            longPool.add(1L);
            longPool.add(-1L);
            floatPool.add(1.0f);
            floatPool.add(-1.0f);
            doublePool.add(1.0);
            doublePool.add(-1.0);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ConstantBag<T> newBag(Supplier<ConstantBag<?>> bags) {
        return (ConstantBag<T>) bags.get();
    }

    @Override
    public String getRandomString() {
        return stringPool.random();
    }

    @Override
    public Type getRandomType() {
        return typePool.random();
    }

    @Override
    public int getRandomInt() {
        return intPool.random();
    }

    @Override
    public float getRandomFloat() {
        return floatPool.random();
    }

    @Override
    public double getRandomDouble() {
        return doublePool.random();
    }

    @Override
    public long getRandomLong() {
        return longPool.random();
    }

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
            Type type = (Type) object;
            if (flattenArrayTypes) {
                while (type.getSort() == Type.ARRAY) {
                    type = type.getElementType();
                }
            }
            typePool.add(type);
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
            logger.debug("Constant of unknown type: {}", object.getClass());
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

        if (intPool.isEmpty()) {
            intPool.add(0);
        }
        if (longPool.isEmpty()) {
            longPool.add(0L);
        }
        if (floatPool.isEmpty()) {
            floatPool.add(0.0f);
        }
        if (doublePool.isEmpty()) {
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

    @Override
    public String toString() {
        return name + ":{stringPool=" + stringPool + " ; typePool=" + typePool + " ; intPool=" + intPool
                + " ; longPool=" + longPool + " ; floatPool=" + floatPool + " ; doublePool=" + doublePool + "}";
    }

}
