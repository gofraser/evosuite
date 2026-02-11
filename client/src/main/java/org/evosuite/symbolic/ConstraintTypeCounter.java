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
package org.evosuite.symbolic;

/**
 * This class is used to count different types of constraints.
 */
public class ConstraintTypeCounter {

    private final int[] countersByType = new int[16];

    private static final int INTEGER_CONSTRAINT_KEY = 0b0001;
    private static final int REAL_CONSTRAINT_KEY = 0b0010;
    private static final int STRING_CONSTRAINT_KEY = 0b0100;
    private static final int REFERENCE_CONSTRAINT_KEY = 0b1000;

    /**
     * Adds a new constraint to the counter.
     *
     * @param isInteger   true if it is an integer constraint.
     * @param isReal      true if it is a real constraint.
     * @param isString    true if it is a string constraint.
     * @param isReference true if it is a reference constraint.
     */
    public void addNewConstraint(boolean isInteger, boolean isReal,
                                 boolean isString, boolean isReference) {
        int key = getKey(isInteger, isReal, isString, isReference);
        countersByType[key]++;
    }

    private static int getKey(boolean isIntegerConstraint,
                              boolean isRealConstraint,
                              boolean isStringConstraint,
                              boolean isReferenceConstraint) {
        int key = 0;
        if (isIntegerConstraint) {
            key = key | INTEGER_CONSTRAINT_KEY;
        }
        if (isRealConstraint) {
            key = key | REAL_CONSTRAINT_KEY;
        }
        if (isStringConstraint) {
            key = key | STRING_CONSTRAINT_KEY;
        }
        if (isReferenceConstraint) {
            key = key | REFERENCE_CONSTRAINT_KEY;
        }
        return key;
    }

    /**
     * Resets all counters to zero.
     */
    public void clear() {
        for (int i = 0; i < countersByType.length; i++) {
            countersByType[i] = 0;
        }
    }

    /**
     * Returns the total number of constraints counted.
     *
     * @return the total number of constraints.
     */
    public int getTotalNumberOfConstraints() {
        int count = 0;
        for (int k : countersByType) {
            count += k;
        }
        return count;
    }

    /**
     * Returns the number of integer-only constraints.
     *
     * @return the number of integer-only constraints.
     */
    public int getIntegerOnlyConstraints() {
        int key = getKey(true, false, false, false);
        return countersByType[key];
    }

    /**
     * Returns the number of real-only constraints.
     *
     * @return the number of real-only constraints.
     */
    public int getRealOnlyConstraints() {
        int key = getKey(false, true, false, false);
        return countersByType[key];
    }

    /**
     * Returns the number of string-only constraints.
     *
     * @return the number of string-only constraints.
     */
    public int getStringOnlyConstraints() {
        int key = getKey(false, false, true, false);
        return countersByType[key];
    }

    /**
     * Returns the number of constraints that involve both integer and real types.
     *
     * @return the number of integer and real constraints.
     */
    public int getIntegerAndRealConstraints() {
        int key = getKey(true, true, false, false);
        return countersByType[key];
    }

    /**
     * Returns the number of constraints that involve both integer and string types.
     *
     * @return the number of integer and string constraints.
     */
    public int getIntegerAndStringConstraints() {
        int key = getKey(true, false, true, false);
        return countersByType[key];
    }

    /**
     * Returns the number of constraints that involve both real and string types.
     *
     * @return the number of real and string constraints.
     */
    public int getRealAndStringConstraints() {
        int key = getKey(false, true, true, false);
        return countersByType[key];
    }

    /**
     * Returns the number of constraints that involve integer, real, and string types.
     *
     * @return the number of integer, real, and string constraints.
     */
    public int getIntegerRealAndStringConstraints() {
        int key = getKey(true, true, true, false);
        return countersByType[key];
    }

    /* Reference Combinations */

    /**
     * Returns the number of reference-only constraints.
     *
     * @return the number of reference-only constraints.
     */
    public int getReferenceOnlyConstraints() {
        int key = getKey(false, false, false, true);
        return countersByType[key];
    }

    /**
     * Returns the number of constraints that involve both integer and reference types.
     *
     * @return the number of integer and reference constraints.
     */
    public int getIntegerAndReferenceConstraints() {
        int key = getKey(true, false, false, true);
        return countersByType[key];
    }

    /**
     * Returns the number of constraints that involve both real and reference types.
     *
     * @return the number of real and reference constraints.
     */
    public int getRealAndReferenceConstraints() {
        int key = getKey(false, true, false, true);
        return countersByType[key];
    }

    /**
     * Returns the number of constraints that involve both string and reference types.
     *
     * @return the number of string and reference constraints.
     */
    public int getStringAndReferenceConstraints() {
        int key = getKey(true, false, true, true);
        return countersByType[key];
    }

    /**
     * Returns the number of constraints that involve integer, real, and reference types.
     *
     * @return the number of integer, real, and reference constraints.
     */
    public int getIntegerRealAndReferenceConstraints() {
        int key = getKey(true, true, false, true);
        return countersByType[key];
    }

    /**
     * Returns the number of constraints that involve integer, string, and reference types.
     *
     * @return the number of integer, string, and reference constraints.
     */
    public int getIntegerStringAndReferenceConstraints() {
        int key = getKey(true, false, true, true);
        return countersByType[key];
    }

    /**
     * Returns the number of constraints that involve real, string, and reference types.
     *
     * @return the number of real, string, and reference constraints.
     */
    public int getRealStringAndReferenceConstraints() {
        int key = getKey(false, true, true, true);
        return countersByType[key];
    }

    /**
     * Returns the number of constraints that involve integer, real, string, and reference types.
     *
     * @return the number of integer, real, string, and reference constraints.
     */
    public int getIntegerRealStringAndReferenceConstraints() {
        int key = getKey(true, true, true, true);
        return countersByType[key];
    }
}

