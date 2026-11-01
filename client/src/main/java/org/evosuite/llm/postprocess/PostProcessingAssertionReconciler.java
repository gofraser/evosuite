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
package org.evosuite.llm.postprocess;

import org.evosuite.assertion.Assertion;
import org.evosuite.assertion.TemplateCodeAssertion;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;

import java.util.HashMap;
import java.util.Map;

/**
 * Neutral suite-level reconciliation for assertions applied by post-processing.
 * It distinguishes assertions still present in stable tests from assertions
 * removed by instability or by the later compile filter.
 */
final class PostProcessingAssertionReconciler {

    private PostProcessingAssertionReconciler() {
        // Utility class.
    }

    static int countTemplateAssertions(TestSuiteChromosome suite) {
        if (suite == null) {
            return 0;
        }
        int count = 0;
        for (TestChromosome chromosome : suite.getTestChromosomes()) {
            if (chromosome != null && chromosome.getTestCase() != null) {
                count += countTemplateAssertions(chromosome.getTestCase());
            }
        }
        return count;
    }

    /**
     * Walks the suite once. {@code shippedBySignature} is only filled when
     * {@code collectSignatures} is set, because rendering each assertion's
     * signature is needed solely for the lifecycle trace.
     */
    static Reconciliation reconcile(TestSuiteChromosome suite, int initiallyAppliedAssertions,
                                    boolean collectSignatures) {
        int shipped = 0;
        int stillPresentButCommented = 0;
        Map<String, Integer> shippedBySignature = new HashMap<>();
        if (suite != null) {
            for (TestChromosome chromosome : suite.getTestChromosomes()) {
                if (chromosome == null || chromosome.getTestCase() == null) {
                    continue;
                }
                TestCase test = chromosome.getTestCase();
                if (test.isUnstable()) {
                    stillPresentButCommented += countTemplateAssertions(test);
                    continue;
                }
                for (Assertion assertion : test.getAssertions()) {
                    if (assertion instanceof TemplateCodeAssertion) {
                        shipped++;
                        if (collectSignatures) {
                            shippedBySignature.merge(LlmPostProcessor.assertionSignatureForSession(
                                    (TemplateCodeAssertion) assertion), 1, Integer::sum);
                        }
                    }
                }
            }
        }
        int removedUnstable = Math.max(0, initiallyAppliedAssertions - shipped);
        removedUnstable = Math.max(removedUnstable, stillPresentButCommented);
        return new Reconciliation(shipped, removedUnstable, shippedBySignature);
    }

    private static int countTemplateAssertions(TestCase test) {
        if (test == null) {
            return 0;
        }
        int count = 0;
        for (Assertion assertion : test.getAssertions()) {
            if (assertion instanceof TemplateCodeAssertion) {
                count++;
            }
        }
        return count;
    }

    static final class Reconciliation {
        private final int shipped;
        private final int removedUnstable;
        private final Map<String, Integer> shippedBySignature;

        private Reconciliation(int shipped, int removedUnstable, Map<String, Integer> shippedBySignature) {
            this.shipped = shipped;
            this.removedUnstable = removedUnstable;
            this.shippedBySignature = shippedBySignature;
        }

        /** Shipped assertions per signature; empty unless the reconciliation collected signatures. */
        Map<String, Integer> shippedBySignature() {
            return shippedBySignature;
        }

        int shipped() {
            return shipped;
        }

        int removedUnstable() {
            return removedUnstable;
        }
    }
}
