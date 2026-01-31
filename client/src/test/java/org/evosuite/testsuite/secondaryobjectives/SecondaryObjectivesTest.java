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
package org.evosuite.testsuite.secondaryobjectives;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SecondaryObjectivesTest {

    private TestChromosome createRealTestChromosome(int size, int exceptions) {
        TestChromosome tc = new TestChromosome();
        TestCase spyTestCase = Mockito.spy(new DefaultTestCase());
        Mockito.doReturn(size).when(spyTestCase).size();

        tc.setTestCase(spyTestCase);

        if (exceptions > 0) {
            ExecutionResult result = new ExecutionResult(spyTestCase);
            for (int i = 0; i < exceptions; i++) {
                result.reportNewThrownException(i, new RuntimeException("Mock exception " + i));
            }
            tc.setLastExecutionResult(result);
        }
        return tc;
    }

    private TestSuiteChromosome createSuite(int... sizes) {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        for (int size : sizes) {
             suite.addTest(createRealTestChromosome(size, 0));
        }
        return suite;
    }

     private TestSuiteChromosome createSuiteWithExceptions(int[] sizes, int[] exceptions) {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        for (int i = 0; i < sizes.length; i++) {
             suite.addTest(createRealTestChromosome(sizes[i], exceptions[i]));
        }
        return suite;
    }

    @Test
    public void testMinimizeTotalLengthCompareGenerations() {
        MinimizeTotalLengthSecondaryObjective obj = new MinimizeTotalLengthSecondaryObjective();

        // Parent 1: Total length 10
        TestSuiteChromosome p1 = createSuite(10);
        // Parent 2: Total length 20
        TestSuiteChromosome p2 = createSuite(20);

        // Child 1: Total length 5
        TestSuiteChromosome c1 = createSuite(5);
        // Child 2: Total length 100
        TestSuiteChromosome c2 = createSuite(100);

        int result = obj.compareGenerations(p1, p2, c1, c2);

        // We expect consistent behavior (MIN comparison).
        // min(10, 20) - min(5, 100) = 10 - 5 = 5.
        // Current implementation does SUM: (30) - (105) = -75.

        Assert.assertTrue("Should favor children because child1 has length 5 which is better than parent1 length 10. Result was: " + result, result > 0);
    }

    @Test
    public void testMinimizeSizeCompareGenerations() {
        MinimizeSizeSecondaryObjective obj = new MinimizeSizeSecondaryObjective();

        TestSuiteChromosome p1 = createSuite(10); // size 1 (1 test of size 10)
        TestSuiteChromosome p2 = createSuite(10, 10); // size 2

        TestSuiteChromosome c1 = createSuite(); // size 0
        TestSuiteChromosome c2 = createSuite(10, 10, 10); // size 3

        // Parent min size: 1
        // Child min size: 0
        // Result: 1 - 0 = 1.

        int result = obj.compareGenerations(p1, p2, c1, c2);
        Assert.assertEquals(1, result);
    }

     @Test
    public void testMinimizeExceptionsCompareGenerations() {
        MinimizeExceptionsSecondaryObjective obj = new MinimizeExceptionsSecondaryObjective();

        // Parent 1: 1 exception
        TestSuiteChromosome p1 = createSuiteWithExceptions(new int[]{1}, new int[]{1});
        // Parent 2: 5 exceptions
        TestSuiteChromosome p2 = createSuiteWithExceptions(new int[]{1}, new int[]{5});

        // Child 1: 0 exceptions
        TestSuiteChromosome c1 = createSuiteWithExceptions(new int[]{1}, new int[]{0});
        // Child 2: 10 exceptions
        TestSuiteChromosome c2 = createSuiteWithExceptions(new int[]{1}, new int[]{10});

        // Parent min: 1
        // Child min: 0
        // Result: 1 - 0 = 1.

        int result = obj.compareGenerations(p1, p2, c1, c2);
        Assert.assertEquals(1, result);
    }

    @Test
    public void testMinimizeMaxLengthCompareGenerations() {
        MinimizeMaxLengthSecondaryObjective obj = new MinimizeMaxLengthSecondaryObjective();

        // Parent 1: Max length 10
        TestSuiteChromosome p1 = createSuite(10);
        // Parent 2: Max length 20
        TestSuiteChromosome p2 = createSuite(20);

        // Child 1: Max length 5
        TestSuiteChromosome c1 = createSuite(5);
        // Child 2: Max length 100
        TestSuiteChromosome c2 = createSuite(100);

        // Parent min: 10
        // Child min: 5
        // Result: 5

        int result = obj.compareGenerations(p1, p2, c1, c2);
        Assert.assertTrue("Should be positive", result > 0);
    }
}
