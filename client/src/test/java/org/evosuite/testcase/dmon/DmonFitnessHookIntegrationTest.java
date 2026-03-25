/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

public class DmonFitnessHookIntegrationTest {

    public static class FitnessTarget {
        static Runnable dep;

        public static void setDep(Runnable r) {
            dep = r;
        }

        public FitnessTarget() {
            dep.run();
        }
    }

    private static final class ProbeFitness extends TestFitnessFunction {
        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return result.noThrownExceptions() ? 0.0 : 1.0;
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            return compareClassName(other);
        }

        @Override
        public int hashCode() {
            return 17;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ProbeFitness;
        }

        @Override
        public String getTargetClass() {
            return Properties.TARGET_CLASS;
        }

        @Override
        public String getTargetMethod() {
            return "<init>";
        }
    }

    @Test
    public void fitnessHookPromotesAndNextEvaluationExecutesPromotedTest() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldOnlyTarget = Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        boolean oldValidate = Properties.DMON_VALIDATE_PROMOTED_ONCE;
        boolean oldReflection = Properties.DMON_ALLOW_REFLECTION_FALLBACK;
        String oldTargetClass = Properties.TARGET_CLASS;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;
            Properties.DMON_VALIDATE_PROMOTED_ONCE = false;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = true;
            Properties.TARGET_CLASS = FitnessTarget.class.getCanonicalName();

            FitnessTarget.dep = null;

            TestChromosome chromosome = new TestChromosome();
            DefaultTestCase tc = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    FitnessTarget.class.getDeclaredConstructor(), FitnessTarget.class);
            tc.addStatement(new ConstructorStatement(tc, gc, Collections.emptyList()));
            chromosome.setTestCase(tc);
            chromosome.setChanged(false);

            ProbeFitness fitness = new ProbeFitness();

            double first = fitness.getFitness(chromosome);
            Assert.assertTrue("First execution should produce in-place promotion", chromosome.isChanged());
            Assert.assertEquals(3, chromosome.getTestCase().size());
            Assert.assertTrue(chromosome.getTestCase().getStatement(0) instanceof FunctionalMockStatement);
            Assert.assertTrue(chromosome.getTestCase().getStatement(1) instanceof MethodStatement);
            Assert.assertTrue(first == 0.0 || first == 1.0);

            double second = fitness.getFitness(chromosome);
            Assert.assertEquals("Promoted test should execute successfully on next evaluation", 0.0, second, 0.0);
            Assert.assertFalse(chromosome.isChanged());
            Assert.assertNotNull(chromosome.getLastExecutionResult());
            Assert.assertTrue(chromosome.getLastExecutionResult().noThrownExceptions());
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = oldOnlyTarget;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
            Properties.DMON_VALIDATE_PROMOTED_ONCE = oldValidate;
            Properties.DMON_ALLOW_REFLECTION_FALLBACK = oldReflection;
            Properties.TARGET_CLASS = oldTargetClass;
            FitnessTarget.dep = null;
            TestCaseExecutor.pullDown();
            TestCaseExecutor.initExecutor();
        }
    }
}
