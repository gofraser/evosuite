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
package org.evosuite.assertion;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * CompleteAssertionGenerator class.
 * </p>
 *
 * @author Gordon Fraser
 */
public class CompleteAssertionGenerator extends AssertionGenerator {

    /*
     * (non-Javadoc)
     *
     * @see
     * org.evosuite.assertion.AssertionGenerator#addAssertions(org.evosuite.
     * testcase.TestCase)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void addAssertions(TestCase test) {
        collectCandidates(test);
        logger.debug("Test after adding assertions: " + test.toCode());
    }

    /**
     * Generate the filtered complete assertion candidate pool and return the
     * execution result that produced it. The supplied test is intentionally the
     * mutation target; callers that only need candidate facts should pass a clone.
     *
     * @param test assertion-free test to execute and annotate with candidates
     * @return candidate assertions and the execution result used to produce them
     */
    public CandidateCollection collectCandidates(TestCase test) {
        ExecutionResult result = runTest(test);
        for (OutputTrace<?> trace : result.getTraces()) {
            trace.getAllAssertions(test);
            trace.clear();
        }
        filterRedundantNonnullAssertions(test);
        filterRedundantChainedInspectorAssertions(test);
        filterRedundantIsEmptySizeAssertions(test);
        for (int i = 0; i < test.size(); i++) {
            filterInspectorPrimitiveDuplication(test.getStatement(i));
            filterVolatileGuiPrimitiveAssertions(test.getStatement(i));
        }
        return new CandidateCollection(test, result, test.getAssertions());
    }

    public static final class CandidateCollection {
        private final TestCase test;
        private final ExecutionResult executionResult;
        private final List<Assertion> assertions;

        public CandidateCollection(TestCase test, ExecutionResult executionResult, List<Assertion> assertions) {
            this.test = test;
            this.executionResult = executionResult;
            this.assertions = Collections.unmodifiableList(new ArrayList<>(assertions));
        }

        public TestCase getTest() {
            return test;
        }

        public ExecutionResult getExecutionResult() {
            return executionResult;
        }

        public List<Assertion> getAssertions() {
            return assertions;
        }
    }
}
