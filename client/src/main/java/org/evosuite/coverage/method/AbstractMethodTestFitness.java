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
package org.evosuite.coverage.method;

import org.evosuite.testcase.TestFitnessFunction;

import java.util.Objects;

/**
 * Abstract base class for method coverage fitness functions.
 *
 * @author Gordon Fraser, Jose Miguel Rojas
 */
public abstract class AbstractMethodTestFitness extends TestFitnessFunction {

    private static final long serialVersionUID = -5635347264626300451L;

    /**
     * Target method.
     */
    protected final String className;
    protected final String methodName;

    public AbstractMethodTestFitness(String className, String methodName) {
        this.className = Objects.requireNonNull(className, "className cannot be null");
        this.methodName = Objects.requireNonNull(methodName, "methodName cannot be null");
    }

    /**
     *
     * <p>getClassName
     * </p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getClassName() {
        return className;
    }

    /**
     *
     * <p>getMethod
     * </p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getMethod() {
        return methodName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return className + "." + methodName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int iConst = 13;
        return 51 * iConst + className.hashCode() * iConst + methodName.hashCode();
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
        AbstractMethodTestFitness other = (AbstractMethodTestFitness) obj;
        if (!className.equals(other.className)) {
            return false;
        } else return methodName.equals(other.methodName);
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.TestFitnessFunction#compareTo(org.evosuite.testcase.TestFitnessFunction)
     */
    @Override
    public int compareTo(TestFitnessFunction other) {
        if (this.getClass().equals(other.getClass())) {
            AbstractMethodTestFitness otherMethodFitness = (AbstractMethodTestFitness) other;
            if (className.equals(otherMethodFitness.getClassName())) {
                return methodName.compareTo(otherMethodFitness.getMethod());
            } else {
                return className.compareTo(otherMethodFitness.getClassName());
            }
        }
        return compareClassName(other);
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.TestFitnessFunction#getTargetClass()
     */
    @Override
    public String getTargetClass() {
        return getClassName();
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.TestFitnessFunction#getTargetMethod()
     */
    @Override
    public String getTargetMethod() {
        return getMethod();
    }
}
