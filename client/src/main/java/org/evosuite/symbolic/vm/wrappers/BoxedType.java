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
package org.evosuite.symbolic.vm.wrappers;

import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * The boxing wrapper classes whose constructor, {@code valueOf} and accessor
 * methods are modelled by storing the symbolic primitive in a heap field of
 * the wrapper object.
 */
public enum BoxedType {

    INTEGER(Integer.class, int.class, SymbolicHeap.$INT_VALUE, "intValue"),
    LONG(Long.class, long.class, SymbolicHeap.$LONG_VALUE, "longValue"),
    FLOAT(Float.class, float.class, SymbolicHeap.$FLOAT_VALUE, "floatValue"),
    DOUBLE(Double.class, double.class, SymbolicHeap.$DOUBLE_VALUE, "doubleValue"),
    SHORT(Short.class, short.class, SymbolicHeap.$SHORT_VALUE, "shortValue"),
    BYTE(Byte.class, byte.class, SymbolicHeap.$BYTE_VALUE, "byteValue"),
    CHARACTER(Character.class, char.class, SymbolicHeap.$CHAR_VALUE, "charValue"),
    BOOLEAN(Boolean.class, boolean.class, SymbolicHeap.$BOOLEAN_VALUE, "booleanValue");

    final String owner;
    final String heapField;
    final String accessorName;
    private final Type primitive;
    private final Type boxed;

    BoxedType(Class<?> boxed, Class<?> primitive, String heapField, String accessorName) {
        this.owner = Type.getInternalName(boxed);
        this.boxed = Type.getType(boxed);
        this.primitive = Type.getType(primitive);
        this.heapField = heapField;
        this.accessorName = accessorName;
    }

    /**
     * Whether the primitive is modelled by a real expression rather than an
     * integer one.
     */
    boolean isReal() {
        return this == FLOAT || this == DOUBLE;
    }

    String initDescriptor() {
        return Type.getMethodDescriptor(Type.VOID_TYPE, primitive);
    }

    String valueOfDescriptor() {
        return Type.getMethodDescriptor(boxed, primitive);
    }

    String accessorDescriptor() {
        return Type.getMethodDescriptor(primitive);
    }

    /**
     * The value an accessor returned concretely, as the long stored in the
     * symbolic constant of an integer-valued wrapper.
     */
    long integerValue(Object concrete) {
        switch (this) {
            case BOOLEAN:
                return (Boolean) concrete ? 1 : 0;
            case SHORT:
                return ((Number) concrete).shortValue();
            case BYTE:
                return ((Number) concrete).byteValue();
            case CHARACTER:
                return (char) ((Number) concrete).intValue();
            default:
                return ((Number) concrete).longValue();
        }
    }

    /**
     * Creates the constructor, {@code valueOf} and accessor models of all
     * wrappers.
     */
    public static List<SymbolicFunction> models(SymbolicEnvironment env) {
        List<SymbolicFunction> models = new ArrayList<>();
        for (BoxedType type : values()) {
            models.add(new BoxedInit(env, type));
            models.add(new BoxedValueOf(env, type));
            models.add(new BoxedValue(env, type));
        }
        return models;
    }

}
