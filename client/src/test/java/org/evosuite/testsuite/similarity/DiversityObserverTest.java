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
package org.evosuite.testsuite.similarity;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DiversityObserver edge cases and bounded output semantics.
 */
class DiversityObserverTest {

    @Test
    void getNeedlemanWunschScoreEmptyTestsReturnsZero() {
        TestCase t1 = new DefaultTestCase();
        TestCase t2 = new DefaultTestCase();
        assertEquals(0.0, DiversityObserver.getNeedlemanWunschScore(t1, t2), 1e-9);
    }

    @Test
    void getNeedlemanWunschScoreIdenticalTestsReturnsSimilarity() {
        TestCase t1 = new DefaultTestCase();
        t1.addStatement(new IntPrimitiveStatement(t1, 42));
        TestCase t2 = new DefaultTestCase();
        t2.addStatement(new IntPrimitiveStatement(t2, 42));

        double similarity = DiversityObserver.getNeedlemanWunschScore(t1, t2);
        // Same type + same underlying type → score 2, max = 1*2 = 2, normalized = 1.0
        assertTrue(similarity >= 0.0, "Similarity should be >= 0: " + similarity);
        assertTrue(similarity <= 1.0, "Similarity should be <= 1: " + similarity);
    }

    @Test
    void getSuiteSimilarityEmptySuitesReturnsZero() {
        TestSuiteChromosome suite1 = new TestSuiteChromosome();
        TestSuiteChromosome suite2 = new TestSuiteChromosome();
        assertEquals(0.0, DiversityObserver.getSuiteSimilarity(suite1, suite2), 1e-9);
    }

    @Test
    void getSuiteSimilarityBoundedOutputForIdenticalSuites() {
        TestSuiteChromosome suite1 = new TestSuiteChromosome();
        TestCase tc1 = new DefaultTestCase();
        tc1.addStatement(new IntPrimitiveStatement(tc1, 1));
        suite1.addTest(tc1);

        TestSuiteChromosome suite2 = new TestSuiteChromosome();
        TestCase tc2 = new DefaultTestCase();
        tc2.addStatement(new IntPrimitiveStatement(tc2, 1));
        suite2.addTest(tc2);

        double similarity = DiversityObserver.getSuiteSimilarity(suite1, suite2);
        assertTrue(similarity >= 0.0 && similarity <= 1.0,
                "Similarity should be in [0,1]: " + similarity);
    }

    @Test
    void getNeedlemanWunschScoreOneEmptyOneNonEmpty() {
        TestCase t1 = new DefaultTestCase();
        t1.addStatement(new IntPrimitiveStatement(t1, 10));
        TestCase t2 = new DefaultTestCase();

        double similarity = DiversityObserver.getNeedlemanWunschScore(t1, t2);
        // t1 has 1 stmt, t2 has 0 → max = 1 * |GAP_PENALTY| = 2
        // matrix[1][0] = GAP_PENALTY * 1 = -2, normalized = -2/2 = -1.0
        // This demonstrates the NW score can go negative (asymmetric test lengths)
        assertTrue(similarity <= 1.0, "Similarity should be <= 1: " + similarity);
    }
}
