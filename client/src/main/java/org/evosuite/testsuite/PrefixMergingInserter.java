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
package org.evosuite.testsuite;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Function;

/**
 * Adds a test to a list of tests unless one is a prefix of the other, in which case the
 * longer test is kept and receives the assertions of the shorter one.
 */
public final class PrefixMergingInserter {

    private PrefixMergingInserter() {
    }

    /**
     * Inserts {@code candidate} into {@code tests}, merging it with an existing test it is a
     * prefix of, or that is a prefix of it, when {@link Properties#CALL_PROBABILITY} is not
     * positive.
     *
     * @param tests the tests inserted so far
     * @param candidate the test to insert
     * @param testOf gives the test case of an element
     * @param logger the logger of the caller, which reports the outcome
     * @param <T> the element type
     * @return the index of the element that now holds the candidate's statements
     */
    public static <T> int insert(List<T> tests, T candidate, Function<T, TestCase> testOf,
                                 Logger logger) {
        TestCase candidateTest = testOf.apply(candidate);
        if (Properties.CALL_PROBABILITY <= 0) {
            for (int i = 0; i < tests.size(); i++) {
                TestCase existingTest = testOf.apply(tests.get(i));
                if (candidateTest.isPrefix(existingTest)) {
                    logger.info("This is a prefix of an existing test");
                    existingTest.addAssertions(candidateTest);
                    return i;
                }
                if (existingTest.isPrefix(candidateTest)) {
                    candidateTest.addAssertions(existingTest);
                    tests.set(i, candidate);
                    logger.info("We have a prefix of this one");
                    return i;
                }
            }
        }
        logger.info("Adding new test case:");
        if (logger.isDebugEnabled()) {
            logger.debug(candidateTest.toCode());
        }
        tests.add(candidate);
        return tests.size() - 1;
    }
}
