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
package org.evosuite.coverage.io;

import org.apache.commons.lang3.ClassUtils;
import org.evosuite.assertion.Inspector;
import org.evosuite.assertion.InspectorManager;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.evosuite.coverage.io.IOCoverageConstants.*;

/**
 * The value categories of input and output coverage. A value, such as a method argument or a return
 * value, is described by one or more of the descriptors in {@link IOCoverageConstants}, depending on
 * its type. The input and output goals share this taxonomy: the goals that can be targeted for a
 * type ({@link #staticDescriptors}) and the goals a concrete value covers ({@link #observedDescriptors})
 * are listed here in the order in which they become goals.
 */
public final class IOValueDescriptors {

    private static final Logger logger = LoggerFactory.getLogger(IOValueDescriptors.class);

    private IOValueDescriptors() {
    }

    /**
     * A category covered by a concrete value, with the numeric value that goal distances are
     * computed from (null for categories that have none).
     */
    public static final class ObservedDescriptor {
        private final String descriptor;
        private final Number numericValue;

        private ObservedDescriptor(String descriptor, Number numericValue) {
            this.descriptor = descriptor;
            this.numericValue = numericValue;
        }

        public String getDescriptor() {
            return descriptor;
        }

        public Number getNumericValue() {
            return numericValue;
        }
    }

    /**
     * Lists the descriptors that can be targeted for values of the given type.
     *
     * @param type        the declared type of the value
     * @param clazz       the declared class of the value
     * @param collections whether List, Set and Map values get descriptors of their own
     * @return the descriptors in goal order
     */
    public static List<String> staticDescriptors(Type type, Class<?> clazz, boolean collections) {
        List<String> descriptors = new ArrayList<>();

        int typeSort = type.getSort();
        if (typeSort == Type.OBJECT && ClassUtils.isPrimitiveWrapper(clazz)) {
            typeSort = Type.getType(ClassUtils.wrapperToPrimitive(clazz)).getSort();
            descriptors.add(REF_NULL);
        }

        switch (typeSort) {
            case Type.BOOLEAN:
                descriptors.add(BOOL_TRUE);
                descriptors.add(BOOL_FALSE);
                break;
            case Type.CHAR:
                descriptors.add(CHAR_ALPHA);
                descriptors.add(CHAR_DIGIT);
                descriptors.add(CHAR_OTHER);
                break;
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
            case Type.FLOAT:
            case Type.LONG:
            case Type.DOUBLE:
                descriptors.add(NUM_NEGATIVE);
                descriptors.add(NUM_ZERO);
                descriptors.add(NUM_POSITIVE);
                break;
            case Type.ARRAY:
                descriptors.add(REF_NULL);
                descriptors.add(ARRAY_EMPTY);
                descriptors.add(ARRAY_NONEMPTY);
                break;
            case Type.OBJECT:
                descriptors.add(REF_NULL);
                if (type.getClassName().equals("java.lang.String")) {
                    descriptors.add(STRING_EMPTY);
                    descriptors.add(STRING_NONEMPTY);
                } else if (collections && List.class.isAssignableFrom(clazz)) {
                    descriptors.add(LIST_EMPTY);
                    descriptors.add(LIST_NONEMPTY);
                } else if (collections && Set.class.isAssignableFrom(clazz)) {
                    descriptors.add(SET_EMPTY);
                    descriptors.add(SET_NONEMPTY);
                } else if (collections && Map.class.isAssignableFrom(clazz)) {
                    descriptors.add(MAP_EMPTY);
                    descriptors.add(MAP_NONEMPTY);
                } else {
                    descriptors.add(REF_NONNULL);
                    for (Inspector inspector : InspectorManager.getInstance().getInspectors(clazz)) {
                        String prefix = inspectorPrefix(type, inspector);
                        Type returnType = Type.getReturnType(inspector.getMethod());
                        if (returnType.getSort() == Type.BOOLEAN) {
                            descriptors.add(prefix + BOOL_TRUE);
                            descriptors.add(prefix + BOOL_FALSE);
                        } else if (returnType.getSort() >= Type.BYTE && returnType.getSort() <= Type.DOUBLE) {
                            descriptors.add(prefix + NUM_NEGATIVE);
                            descriptors.add(prefix + NUM_ZERO);
                            descriptors.add(prefix + NUM_POSITIVE);
                        }
                    }
                }
                break;
            default:
                break;
        }
        return descriptors;
    }

    /**
     * Lists the descriptors that a concrete value covers.
     *
     * @param type        the declared type of the value
     * @param value       the value, possibly null
     * @param collections whether List, Set and Map values get descriptors of their own
     * @return the covered descriptors in goal order
     */
    @SuppressWarnings("rawtypes")
    public static List<ObservedDescriptor> observedDescriptors(Type type, Object value, boolean collections) {
        List<ObservedDescriptor> descriptors = new ArrayList<>();

        if (value == null) {
            descriptors.add(new ObservedDescriptor(REF_NULL, null));
            return descriptors;
        }

        int typeSort = type.getSort();
        if (typeSort == Type.OBJECT) { // value is known not to be null here
            if (ClassUtils.isPrimitiveWrapper(value.getClass())) {
                typeSort = Type.getType(ClassUtils.wrapperToPrimitive(value.getClass())).getSort();
            } else {
                // It is an object and not null, so it covers REF_NONNULL
                descriptors.add(new ObservedDescriptor(REF_NONNULL, null));
            }
        }

        switch (typeSort) {
            case Type.BOOLEAN:
                descriptors.add(new ObservedDescriptor((boolean) value ? BOOL_TRUE : BOOL_FALSE, null));
                break;
            case Type.CHAR:
                char c = (char) value;
                // Fitness uses the numeric representation to estimate distances
                descriptors.add(new ObservedDescriptor(charDescriptor(c), (int) c));
                break;
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
            case Type.FLOAT:
            case Type.LONG:
            case Type.DOUBLE:
                double number;
                if (value instanceof Character) {
                    number = ((Number) ((int) (char) value)).doubleValue();
                } else {
                    number = ((Number) value).doubleValue();
                }
                descriptors.add(new ObservedDescriptor(numberDescriptor(number), number));
                break;
            case Type.ARRAY:
                descriptors.add(new ObservedDescriptor(Array.getLength(value) == 0 ? ARRAY_EMPTY : ARRAY_NONEMPTY,
                        null));
                break;
            case Type.OBJECT:
                if (type.getClassName().equals("java.lang.String")) {
                    descriptors.add(new ObservedDescriptor(((String) value).isEmpty() ? STRING_EMPTY
                            : STRING_NONEMPTY, null));
                } else if (collections && value instanceof List) {
                    descriptors.add(new ObservedDescriptor(((List) value).isEmpty() ? LIST_EMPTY : LIST_NONEMPTY,
                            null));
                } else if (collections && value instanceof Set) {
                    descriptors.add(new ObservedDescriptor(((Set) value).isEmpty() ? SET_EMPTY : SET_NONEMPTY,
                            null));
                } else if (collections && value instanceof Map) {
                    descriptors.add(new ObservedDescriptor(((Map) value).isEmpty() ? MAP_EMPTY : MAP_NONEMPTY,
                            null));
                } else {
                    for (Inspector inspector : InspectorManager.getInstance().getInspectors(value.getClass())) {
                        String prefix = inspectorPrefix(type, inspector);
                        try {
                            Object inspected = inspector.getValue(value);
                            if (inspected instanceof Boolean) {
                                descriptors.add(new ObservedDescriptor(prefix
                                        + (((boolean) inspected) ? BOOL_TRUE : BOOL_FALSE), null));
                            } else if (isJavaNumber(inspected)) {
                                descriptors.add(new ObservedDescriptor(prefix
                                        + numberDescriptor(((Number) inspected).doubleValue()), null));
                            }
                        } catch (InvocationTargetException | IllegalAccessException e) {
                            // Exceptions in inspectors can happen
                            logger.debug(e.getMessage(), e);
                        }
                    }
                }
                break;
            default:
                break;
        }
        return descriptors;
    }

    private static String inspectorPrefix(Type type, Inspector inspector) {
        return REF_NONNULL + ":" + type.getClassName() + ":" + inspector.getMethodCall()
                + Type.getMethodDescriptor(inspector.getMethod()) + ":";
    }

    private static String numberDescriptor(double value) {
        return (value < 0) ? NUM_NEGATIVE : (value == 0) ? NUM_ZERO : NUM_POSITIVE;
    }

    private static String charDescriptor(char c) {
        if (Character.isAlphabetic(c)) {
            return CHAR_ALPHA;
        } else if (Character.isDigit(c)) {
            return CHAR_DIGIT;
        }
        return CHAR_OTHER;
    }

    /**
     * The SUT could have classes extending Number. Calling doubleValue()
     * on those would lead to many problems, like for example security and
     * loop counter checks.
     *
     * @param val the value to check
     * @return true if the value is a Java Number from the java package
     */
    private static boolean isJavaNumber(Object val) {
        return val instanceof Number && val.getClass().getName().startsWith("java.");
    }
}
