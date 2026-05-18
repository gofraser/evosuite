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
package org.evosuite.statistics;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

public class StatisticsListenerTest {

    @Test
    public void shouldEnqueueSnapshotInsteadOfSharedIndividual() throws Exception {
        StatisticsListener<TestSuiteChromosome> listener = new StatisticsListener<>();
        TestSuiteChromosome original = new TestSuiteChromosome();
        original.setFitness(null, 1.0);

        listener.fitnessEvaluation(original);

        TestCase test = new DefaultTestCase();
        PrimitiveStatement<?> statement = PrimitiveStatement.getPrimitiveStatement(test, int.class);
        test.addStatement(statement);
        original.addTest(test);

        TestSuiteChromosome queued = pollQueuedIndividual(listener);
        assertNotSame(original, queued);
        assertEquals(0, queued.size());
        assertEquals(1, original.size());
    }

    @SuppressWarnings("unchecked")
    private static TestSuiteChromosome pollQueuedIndividual(StatisticsListener<TestSuiteChromosome> listener)
            throws Exception {
        Field queueField = StatisticsListener.class.getDeclaredField("individuals");
        queueField.setAccessible(true);
        BlockingQueue<TestSuiteChromosome> queue = (BlockingQueue<TestSuiteChromosome>) queueField.get(listener);
        return queue.poll();
    }
}
