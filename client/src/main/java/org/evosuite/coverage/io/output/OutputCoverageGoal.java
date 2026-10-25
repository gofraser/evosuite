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
package org.evosuite.coverage.io.output;


import org.evosuite.coverage.io.IOValueDescriptors;
import org.evosuite.setup.DependencyAnalysis;
import org.objectweb.asm.Type;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A single output coverage goal.
 * Evaluates the value depending on the return type of the method.
 *
 * @author Gordon Fraser, Andre Mis, Jose Miguel Rojas
 */
public class OutputCoverageGoal implements Serializable, Comparable<OutputCoverageGoal> {

    private static final long serialVersionUID = 3539419075883329059L;

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

        if (returnValue == null && returnType.getSort() != Type.ARRAY && returnType.getSort() != Type.OBJECT) {
            // only references can be null
            return goals;
        }

        for (IOValueDescriptors.ObservedDescriptor observed
                : IOValueDescriptors.observedDescriptors(returnType, returnValue, false)) {
            goals.add(new OutputCoverageGoal(className, methodNameWithDesc, returnType,
                    observed.getDescriptor(), observed.getNumericValue()));
        }
        return goals;
    }
}
