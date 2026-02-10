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
package org.evosuite.coverage.io.output;


import org.apache.commons.lang3.ClassUtils;
import org.evosuite.assertion.Inspector;
import org.evosuite.assertion.InspectorManager;
import org.evosuite.setup.DependencyAnalysis;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static org.evosuite.coverage.io.IOCoverageConstants.*;

/**
 * A single output coverage goal.
 * Evaluates the value depending on the return type of the method.
 *
 * @author Gordon Fraser, Andre Mis, Jose Miguel Rojas
 */
public class OutputCoverageGoal implements Serializable, Comparable<OutputCoverageGoal> {

    private static final long serialVersionUID = 3539419075883329059L;

    private static final Logger logger = LoggerFactory.getLogger(OutputCoverageGoal.class);

    private final String className;
    private final String methodName;
    private final String type;
    private final String valueDescriptor;
    private final Number numericValue;

    /**
     * Can be used to create an arbitrary {@code OutputCoverageGoal} trying to cover the
     * method such that it returns a given {@code value}.
     * <p/>
     * <p/>
     * If the method returns a boolean, this goal will try to cover the method with either {@code true} or {@code false}
     * If the given branch is {@code null}, this goal will try to cover the root branch
     * of the method identified by the given name - meaning it will just try to
     * call the method at hand
     * <p/>
     * <p/>
     * Otherwise this goal will try to reach the given branch and if value is
     * true, make the branchInstruction jump and visa versa
     *
     * @param className       a {@link java.lang.String} object.
     * @param methodName      a {@link java.lang.String} object.
     * @param type            a {@link Type} object.
     * @param valueDescriptor a value descriptor.
     */
    public OutputCoverageGoal(String className, String methodName, Type type, String valueDescriptor) {
        this(className, methodName, type, valueDescriptor, null);
    }

    /**
     * Creates an output coverage goal.
     *
     * @param className       a {@link java.lang.String} object.
     * @param methodName      a {@link java.lang.String} object.
     * @param type            a {@link org.objectweb.asm.Type} object.
     * @param valueDescriptor a value descriptor.
     * @param numericValue    a {@link java.lang.Number} object.
     */
    public OutputCoverageGoal(String className, String methodName, Type type, String valueDescriptor,
                              Number numericValue) {
        if (className == null || methodName == null) {
            throw new IllegalArgumentException("null given");
        }

        this.className = className;
        this.methodName = methodName;
        this.type = type.toString();
        this.valueDescriptor = valueDescriptor;
        this.numericValue = numericValue;
    }

    /**
     * Gets the class name.
     *
     * @return the className.
     */
    public String getClassName() {
        return className;
    }

    /**
     * Gets the method name.
     *
     * @return the methodName.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Gets the return type.
     *
     * @return the type.
     */
    public Type getType() {
        return Type.getType(type);
    }

    /**
     * Gets the value descriptor.
     *
     * @return the value descriptor.
     */
    public String getValueDescriptor() {
        return valueDescriptor;
    }

    /**
     * Gets the numeric value.
     *
     * @return the numeric value.
     */
    public Number getNumericValue() {
        return numericValue;
    }

    // inherited from Object

    /**
     * {@inheritDoc}
     * <p/>
     * Readable representation.
     */
    @Override
    public String toString() {
        return className + "." + methodName + ":" + valueDescriptor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, type, valueDescriptor);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        OutputCoverageGoal other = (OutputCoverageGoal) obj;

        if (!Objects.equals(this.className, other.className)) {
            return false;
        }

        if (!Objects.equals(this.methodName, other.methodName)) {
            return false;
        }

        if (!Objects.equals(this.type, other.type)) {
            return false;
        }

        return Objects.equals(this.valueDescriptor, other.valueDescriptor);
    }

    /**
     * Checks if this goal refers to the same return value target as the other goal,
     * ignoring the value descriptor.
     *
     * @param other the other goal
     * @return true if class name and method name match
     */
    public boolean isSameArgument(OutputCoverageGoal other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        if (!Objects.equals(this.className, other.className)) {
            return false;
        }
        if (!Objects.equals(this.methodName, other.methodName)) {
            return false;
        }
        return true;
    }

    @Override
    public int compareTo(OutputCoverageGoal o) {
        int diff = className.compareTo(o.className);
        if (diff != 0) {
            return diff;
        }

        diff = methodName.compareTo(o.methodName);
        if (diff != 0) {
            return diff;
        }

        diff = type.compareTo(o.type);
        if (diff != 0) {
            return diff;
        }

        if (valueDescriptor == null && o.valueDescriptor == null) {
            return 0;
        }
        if (valueDescriptor == null) {
            return -1;
        }
        if (o.valueDescriptor == null) {
            return 1;
        }
        return valueDescriptor.compareTo(o.valueDescriptor);
    }

    /**
     * Creates a set of coverage goals from an object.
     *
     * @param className   the class name
     * @param methodName  the method name
     * @param methodDesc  the method descriptor
     * @param returnValue the return value
     * @return a set of output coverage goals
     */
    public static Set<OutputCoverageGoal> createGoalsFromObject(String className, String methodName,
                                                                String methodDesc, Object returnValue) {

        Set<OutputCoverageGoal> goals = new LinkedHashSet<>();

        if (!DependencyAnalysis.isTargetClassName(className)) {
            return goals;
        }
        if (methodName.equals("hashCode")) {
            return goals;
        }

        String methodNameWithDesc = methodName + methodDesc;
        Type returnType = Type.getReturnType(methodDesc);

        int typeSort = returnType.getSort();
        if (typeSort == Type.OBJECT && returnValue != null) {
            if (ClassUtils.isPrimitiveWrapper(returnValue.getClass())) {
                typeSort = Type.getType(ClassUtils.wrapperToPrimitive(returnValue.getClass())).getSort();
            }
        }
        switch (typeSort) {
            case Type.BOOLEAN:
                String desc = ((boolean) returnValue) ? BOOL_TRUE : BOOL_FALSE;
                goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType, desc));
                break;
            case Type.CHAR:
                char c = (char) returnValue;
                Number numberValue = (int) c;
                if (Character.isAlphabetic(c)) {
                    goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType, CHAR_ALPHA,
                            numberValue));
                } else if (Character.isDigit(c)) {
                    goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType, CHAR_DIGIT,
                            numberValue));
                } else {
                    goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType, CHAR_OTHER,
                            numberValue));
                }
                break;
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
            case Type.FLOAT:
            case Type.LONG:
            case Type.DOUBLE:
                assert (returnValue instanceof Number);
                if (isJavaNumber(returnValue)) {
                    double value = ((Number) returnValue).doubleValue();
                    String numDesc = (value < 0) ? NUM_NEGATIVE : (value == 0) ? NUM_ZERO : NUM_POSITIVE;
                    goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType, numDesc,
                            (Number) returnValue));
                }
                break;
            case Type.ARRAY:
                if (returnValue == null) {
                    goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType, REF_NULL));
                } else {
                    String arrDesc = (Array.getLength(returnValue) == 0) ? ARRAY_EMPTY : ARRAY_NONEMPTY;
                    goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType, arrDesc));
                }
                break;
            case Type.OBJECT:
                if (returnValue == null) {
                    goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType, REF_NULL));
                } else {
                    goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType, REF_NONNULL));
                    if (returnType.getClassName().equals("java.lang.String")) {
                        String valDesc = ((String) returnValue).isEmpty() ? STRING_EMPTY : STRING_NONEMPTY;
                        goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType, valDesc));
                        break;
                    }
                    for (Inspector inspector : InspectorManager.getInstance().getInspectors(returnValue.getClass())) {
                        String insp = inspector.getMethodCall()
                                + Type.getMethodDescriptor(inspector.getMethod());
                        try {
                            Object val = inspector.getValue(returnValue);
                            if (val instanceof Boolean) {
                                String valDesc = ((boolean) val) ? BOOL_TRUE : BOOL_FALSE;
                                goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType,
                                        REF_NONNULL + ":" + returnType.getClassName() + ":" + insp + ":" + valDesc));
                            } else if (isJavaNumber(val)) {
                                double dv = ((Number) val).doubleValue();
                                String valDesc = (dv < 0) ? NUM_NEGATIVE : (dv == 0) ? NUM_ZERO : NUM_POSITIVE;
                                goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType,
                                        REF_NONNULL + ":" + returnType.getClassName() + ":" + insp + ":" + valDesc));
                            }
                        } catch (InvocationTargetException e) {
                            // Exceptions in inspectors can happen
                            logger.debug(e.getMessage(), e);
                        } catch (IllegalAccessException e) {
                            logger.warn(e.getMessage(), e);
                        }
                    }

                }
                break;
            default:
                // IGNORE
                break;
        }
        return goals;
    }

    /**
     * The SUT could have classes extending Number. Calling doubleValue()
     * on those would lead to many problems, like for example security and
     * loop counter checks.
     *
     * @param val the object to check
     * @return true if it is a standard Java number
     */
    private static boolean isJavaNumber(Object val) {
        return val instanceof Number && val.getClass().getName().startsWith("java.");
    }
}
