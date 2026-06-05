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
 * You should have received a copy of the GNU Lesser General Public License
 * along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.llm.LlmService;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AsyncLlmTestProducerThreadSafetyTest {

    @Test
    @SuppressWarnings("unchecked")
    void safePopulationSnapshotDoesNotMutateLiveChromosomes() throws Exception {
        boolean oldSelection = Properties.LLM_RELEVANCE_BASED_TEST_SELECTION;
        Properties.LLM_RELEVANCE_BASED_TEST_SELECTION = true;
        try {
            TestChromosome live = new TestChromosome();
            TestFitnessFunction goal = new FixedFitnessGoal("example.Target", "method()V", 1.0);

            AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                    () -> Collections.singleton(goal),
                    () -> Collections.singletonList(live),
                    mock(LlmService.class),
                    1,
                    1,
                    0);

            Method snapshotMethod = AsyncLlmTestProducer.class
                    .getDeclaredMethod("safeChromosomeSnapshot");
            snapshotMethod.setAccessible(true);
            List<TestChromosome> detached = (List<TestChromosome>) snapshotMethod.invoke(producer);

            Method projectMethod = AsyncLlmTestProducer.class
                    .getDeclaredMethod("projectTestCases", List.class, Collection.class);
            projectMethod.setAccessible(true);
            List<TestCase> tests = (List<TestCase>) projectMethod.invoke(
                    producer, detached, Collections.singleton(goal));

            assertEquals(1, tests.size(), "Expected one test from population snapshot");
            assertTrue(live.getFitnessValues().isEmpty(),
                    "Live chromosome fitness cache must not be mutated by async snapshot ranking");
        } finally {
            Properties.LLM_RELEVANCE_BASED_TEST_SELECTION = oldSelection;
        }
    }

    private static final class FixedFitnessGoal extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;
        private final String targetClass;
        private final String targetMethod;
        private final double fitness;

        private FixedFitnessGoal(String targetClass, String targetMethod, double fitness) {
            this.targetClass = targetClass;
            this.targetMethod = targetMethod;
            this.fitness = fitness;
        }

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return fitness;
        }

        @Override
        public ExecutionResult runTest(org.evosuite.testcase.TestCase test) {
            return new ExecutionResult(test);
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            return 0;
        }

        @Override
        public int hashCode() {
            int result = targetClass.hashCode();
            result = 31 * result + targetMethod.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FixedFitnessGoal)) {
                return false;
            }
            FixedFitnessGoal that = (FixedFitnessGoal) other;
            return targetClass.equals(that.targetClass) && targetMethod.equals(that.targetMethod);
        }

        @Override
        public String getTargetClass() {
            return targetClass;
        }

        @Override
        public String getTargetMethod() {
            return targetMethod;
        }
    }
}
