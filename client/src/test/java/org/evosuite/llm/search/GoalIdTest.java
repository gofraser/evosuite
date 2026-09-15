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
package org.evosuite.llm.search;

import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.branch.OnlyBranchCoverageTestFitness;
import org.evosuite.coverage.cbranch.CBranchTestFitness;
import org.evosuite.coverage.ibranch.IBranchTestFitness;
import org.evosuite.coverage.io.IOCoverageConstants;
import org.evosuite.coverage.io.output.OutputCoverageGoal;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationTestFitness;
import org.evosuite.setup.CallContext;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoalIdTest {

    private static final String CLASS_NAME = "com.example.Foo";
    private static final String METHOD_NAME = "bar()V";

    @Test
    void nullGoalReturnsEmptyString() {
        assertEquals("", GoalId.of(null));
    }

    @Test
    void lineGoalUsesLineDiscriminator() {
        LineCoverageTestFitness line = new LineCoverageTestFitness(CLASS_NAME, METHOD_NAME, 42);
        assertEquals("Line|" + CLASS_NAME + "|" + METHOD_NAME + "|line=42", GoalId.of(line));
    }

    @Test
    void methodGoalHasEmptyDiscriminator() {
        MethodCoverageTestFitness method = new MethodCoverageTestFitness(CLASS_NAME, METHOD_NAME);
        assertEquals("Method|" + CLASS_NAME + "|" + METHOD_NAME + "|", GoalId.of(method));
    }

    @Test
    void outputGoalUsesTypeAndValueDescriptor() {
        OutputCoverageGoal outputGoal = new OutputCoverageGoal(CLASS_NAME, METHOD_NAME,
                Type.getType("Ljava/lang/String;"), IOCoverageConstants.REF_NONNULL);
        OutputCoverageTestFitness output = new OutputCoverageTestFitness(outputGoal);

        String expected = "Output|" + CLASS_NAME + "|" + METHOD_NAME + "|"
                + outputGoal.getType() + ":" + IOCoverageConstants.REF_NONNULL;
        assertEquals(expected, GoalId.of(output));
    }

    @Test
    void branchlessRootBranchUsesRootDiscriminator() {
        BranchCoverageGoal rootGoal = new BranchCoverageGoal(null, true, CLASS_NAME, METHOD_NAME, 10);
        BranchCoverageTestFitness branch = new BranchCoverageTestFitness(rootGoal);
        assertEquals("Branch|" + CLASS_NAME + "|" + METHOD_NAME + "|branch=root:true", GoalId.of(branch));
    }

    @Test
    void onlyBranchAndIBranchShareTheBranchFamilyDiscriminator() {
        BranchCoverageGoal goal = new BranchCoverageGoal(null, true, CLASS_NAME, METHOD_NAME, 10);
        OnlyBranchCoverageTestFitness onlyBranch = new OnlyBranchCoverageTestFitness(goal);
        assertEquals("OnlyBranch|" + CLASS_NAME + "|" + METHOD_NAME + "|branch=root:true",
                GoalId.of(onlyBranch));

        IBranchTestFitness ibranch = new IBranchTestFitness(goal, new CallContext());
        assertEquals("IBranch|" + CLASS_NAME + "|" + METHOD_NAME + "|branch=root:true|ctx=",
                GoalId.of(ibranch));
    }

    @Test
    void cBranchGoalIncludesCallContext() {
        BranchCoverageGoal goal = new BranchCoverageGoal(null, true, CLASS_NAME, METHOD_NAME, 10);
        CallContext context = new CallContext("com.example.Caller", "caller()V");
        CBranchTestFitness cBranch = new CBranchTestFitness(goal, context);

        String expected = "CBranch|" + CLASS_NAME + "|" + METHOD_NAME
                + "|branch=root:true|ctx=" + context;
        assertEquals(expected, GoalId.of(cBranch));
    }

    @Test
    void iBranchGoalsWithDifferentContextsHaveDifferentIds() {
        BranchCoverageGoal goal = new BranchCoverageGoal(null, true, CLASS_NAME, METHOD_NAME, 10);
        IBranchTestFitness first = new IBranchTestFitness(goal, new CallContext("com.example.CallerA", "a()V"));
        IBranchTestFitness second = new IBranchTestFitness(goal, new CallContext("com.example.CallerB", "b()V"));

        assertNotEquals(GoalId.of(first), GoalId.of(second));
    }

    @Test
    void iBranchGoalsWithTheSameContextHaveTheSameId() {
        BranchCoverageGoal goal = new BranchCoverageGoal(null, true, CLASS_NAME, METHOD_NAME, 10);
        IBranchTestFitness first = new IBranchTestFitness(goal, new CallContext("com.example.Caller", "caller()V"));
        IBranchTestFitness second = new IBranchTestFitness(goal, new CallContext("com.example.Caller", "caller()V"));

        assertEquals(GoalId.of(first), GoalId.of(second));
    }

    @Test
    void mutationGoalUsesMutationId() {
        Mutation mutation = mock(Mutation.class);
        when(mutation.getId()).thenReturn(42);
        MutationTestFitness mutationGoal = mock(MutationTestFitness.class);
        when(mutationGoal.getMutation()).thenReturn(mutation);
        when(mutationGoal.getTargetClass()).thenReturn(CLASS_NAME);
        when(mutationGoal.getTargetMethod()).thenReturn(METHOD_NAME);

        // The concrete class of a mock is not one of the well-known Weak/Strong/OnlyMutation
        // subclasses, so only the class/method/discriminator suffix is checked here.
        assertTrue(GoalId.of(mutationGoal).endsWith("|" + CLASS_NAME + "|" + METHOD_NAME + "|mutation=42"));
    }

    @Test
    void differentKindsOnTheSameMethodProduceDifferentIds() {
        LineCoverageTestFitness line = new LineCoverageTestFitness(CLASS_NAME, METHOD_NAME, 42);
        MethodCoverageTestFitness method = new MethodCoverageTestFitness(CLASS_NAME, METHOD_NAME);

        assertNotEquals(GoalId.of(line), GoalId.of(method));
    }

    @Test
    void unknownGoalFallsBackToTextOfToString() {
        TestFitnessFunction unknown = mock(TestFitnessFunction.class);
        when(unknown.getTargetClass()).thenReturn("com.example.Unknown");
        when(unknown.getTargetMethod()).thenReturn("baz()V");
        when(unknown.toString()).thenReturn("Unknown goal display");

        String id = GoalId.of(unknown);
        assertTrue(id.endsWith("|com.example.Unknown|baz()V|text=Unknown goal display"),
                "an unrecognized goal type must fall back to its toString(): " + id);
    }
}
