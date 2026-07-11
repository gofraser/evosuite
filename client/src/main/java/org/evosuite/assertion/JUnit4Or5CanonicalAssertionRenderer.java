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
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.assertion;

import org.evosuite.llm.postprocess.LlmPostProcessingResponse;

final class JUnit4Or5CanonicalAssertionRenderer implements CanonicalAssertionRenderer {

    static final JUnit4Or5CanonicalAssertionRenderer INSTANCE = new JUnit4Or5CanonicalAssertionRenderer();

    private JUnit4Or5CanonicalAssertionRenderer() {
    }

    @Override
    public String render(LlmPostProcessingResponse.AssertionKind kind,
                         String expected,
                         String actual,
                         String delta) {
        return render(kind, expected, actual, delta, false);
    }

    @Override
    public String render(LlmPostProcessingResponse.AssertionKind kind,
                         String expected,
                         String actual,
                         String delta,
                         boolean arrayEquality) {
        switch (kind) {
            case EQUALS:
                if (arrayEquality) {
                    if (delta != null) {
                        throw new IllegalStateException("Array equality does not support delta");
                    }
                    return "assertArrayEquals(" + expected + ", " + actual + ");";
                }
                return delta == null
                        ? "assertEquals(" + expected + ", " + actual + ");"
                        : "assertEquals(" + expected + ", " + actual + ", " + delta + ");";
            case NOT_EQUALS:
                return delta == null
                        ? "assertNotEquals(" + expected + ", " + actual + ");"
                        : "assertNotEquals(" + expected + ", " + actual + ", " + delta + ");";
            case TRUE:
                return "assertTrue(" + actual + ");";
            case FALSE:
                return "assertFalse(" + actual + ");";
            case NULL:
                return "assertNull(" + actual + ");";
            case NOT_NULL:
                return "assertNotNull(" + actual + ");";
            case SAME:
                return "assertSame(" + expected + ", " + actual + ");";
            case NOT_SAME:
                return "assertNotSame(" + expected + ", " + actual + ");";
            case CONTAINS:
                return "assertTrue(" + actual + ".contains(" + expected + "));";
            case NOT_CONTAINS:
                return "assertFalse(" + actual + ".contains(" + expected + "));";
            case SIZE_EQUALS:
                return "assertEquals(" + expected + ", " + actual + ".size());";
            case MAP_CONTAINS_KEY:
                return "assertTrue(" + actual + ".containsKey(" + expected + "));";
            case IS_EMPTY:
                return "assertTrue(" + actual + ".isEmpty());";
            case GREATER:
                return "assertTrue(" + actual + " > " + expected + ");";
            case LESS:
                return "assertTrue(" + actual + " < " + expected + ");";
            case GREATER_EQUALS:
                return "assertTrue(" + actual + " >= " + expected + ");";
            case LESS_EQUALS:
                return "assertTrue(" + actual + " <= " + expected + ");";
            default:
                throw new IllegalStateException("Unsupported template assertion kind: " + kind);
        }
    }
}
