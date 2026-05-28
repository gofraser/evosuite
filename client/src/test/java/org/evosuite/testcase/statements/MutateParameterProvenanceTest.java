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
package org.evosuite.testcase.statements;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for {@link EntityWithParametersStatement#mutateParameter}:
 * when the mutation substitutes a parameter with a freshly created {@link NullStatement},
 * that NullStatement should inherit the LLM provenance flag of the enclosing
 * (LLM-derived) call. Otherwise the population's LLM_Parsed_Statement_Ratio
 * decays every time an LLM-marked call gets its parameter swapped to null.
 */
public class MutateParameterProvenanceTest {

    @SuppressWarnings("unused")
    public static class Target {
        public static int takesString(String s) {
            return s == null ? 0 : s.length();
        }
    }

    @Test
    public void mutateParameterMarksFreshNullStatementWithParentProvenance() throws Exception {
        // Iterate over random seeds until we hit the path that picks the
        // freshly-created NullStatement. The path is reachable but
        // probabilistic; trying a bounded set of seeds keeps the test
        // deterministic without needing to mock Randomness.
        boolean nullPathExercised = false;
        for (long seed = 0; seed < 500 && !nullPathExercised; seed++) {
            DefaultTestCase tc = buildScenario();
            MethodStatement methodStmt = (MethodStatement) tc.getStatement(tc.size() - 1);
            int insertPos = methodStmt.getPosition();

            Randomness.setSeed(seed);
            int sizeBefore = tc.size();
            boolean changed = methodStmt.mutateParameter(tc, 0);
            int sizeAfter = tc.size();

            if (!changed || sizeAfter <= sizeBefore) {
                continue;
            }
            // mutateParameter inserts the chosen substitute at the method's
            // original position, pushing the method one slot further.
            Statement inserted = tc.getStatement(insertPos);
            if (inserted instanceof NullStatement) {
                nullPathExercised = true;
                assertTrue(inserted.isParsedFromLlm(),
                        "NullStatement inserted by mutateParameter for an LLM-marked"
                                + " call should carry the LLM flag (seed=" + seed + ")");
            }
        }
        assertTrue(nullPathExercised,
                "Could not exercise the NullStatement substitution path with seeds 0..499");
    }

    @Test
    public void mutateParameterDoesNotMarkNullStatementForNonLlmCall() throws Exception {
        // Negative case: when the parent call is NOT LLM-derived, the
        // inserted NullStatement must remain unmarked. This guards against
        // an overzealous fix that would set the flag unconditionally.
        boolean nullPathExercised = false;
        for (long seed = 0; seed < 500 && !nullPathExercised; seed++) {
            DefaultTestCase tc = buildScenario();
            MethodStatement methodStmt = (MethodStatement) tc.getStatement(tc.size() - 1);
            methodStmt.setParsedFromLlm(false);
            int insertPos = methodStmt.getPosition();

            Randomness.setSeed(seed);
            int sizeBefore = tc.size();
            boolean changed = methodStmt.mutateParameter(tc, 0);
            int sizeAfter = tc.size();

            if (!changed || sizeAfter <= sizeBefore) {
                continue;
            }
            Statement inserted = tc.getStatement(insertPos);
            if (inserted instanceof NullStatement) {
                nullPathExercised = true;
                assertTrue(!inserted.isParsedFromLlm(),
                        "NullStatement inserted by mutateParameter for a non-LLM"
                                + " call should remain unmarked (seed=" + seed + ")");
            }
        }
        assertTrue(nullPathExercised,
                "Could not exercise the NullStatement substitution path with seeds 0..499");
    }

    /**
     * Build the same scenario each call: a string parameter + a method that
     * takes one string, with the method marked as LLM-derived.
     */
    private DefaultTestCase buildScenario() throws NoSuchMethodException {
        DefaultTestCase tc = new DefaultTestCase();
        StringPrimitiveStatement str = new StringPrimitiveStatement(tc, "hello");
        tc.addStatement(str);

        Method m = Target.class.getMethod("takesString", String.class);
        GenericMethod gm = new GenericMethod(m, Target.class);
        List<VariableReference> params = new ArrayList<>();
        params.add(str.getReturnValue());
        MethodStatement methodStmt = new MethodStatement(tc, gm, null, params);
        methodStmt.setParsedFromLlm(true);
        tc.addStatement(methodStmt);

        return tc;
    }
}
