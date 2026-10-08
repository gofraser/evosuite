/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
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
