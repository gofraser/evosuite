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
package org.evosuite.testcase.synthesizer;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.variable.VariableReference;

/**
 * Small shared helper for locating a variable definition in a test case.
 */
public final class ConstructionReferenceUtil {

    private ConstructionReferenceUtil() {
    }

    /**
     * Returns the first statement defining {@code reference}, or {@code -1} if absent.
     */
    public static int definitionPosition(TestCase test, VariableReference reference) {
        if (test == null || reference == null) {
            return -1;
        }
        for (int position = 0; position < test.size(); position++) {
            if (test.getStatement(position).getReturnValue() == reference) {
                return position;
            }
        }
        for (int position = 0; position < test.size(); position++) {
            VariableReference candidate = test.getStatement(position).getReturnValue();
            if (candidate != null && candidate.equals(reference)) {
                return position;
            }
        }
        return -1;
    }

    /**
     * Returns whether both references denote the same definition in {@code test}: the same
     * object, or two references whose defining statement is the same.
     */
    public static boolean sameReference(
            TestCase test, VariableReference left, VariableReference right) {
        if (left == right) {
            return left != null;
        }
        int leftPosition = definitionPosition(test, left);
        int rightPosition = definitionPosition(test, right);
        return leftPosition >= 0 && leftPosition == rightPosition;
    }

    /**
     * Returns whether {@code references} holds {@code candidate} itself (not merely an equal one).
     */
    public static boolean containsIdentity(
            Iterable<VariableReference> references, VariableReference candidate) {
        if (references == null) {
            return false;
        }
        for (VariableReference reference : references) {
            if (reference == candidate) {
                return true;
            }
        }
        return false;
    }
}
