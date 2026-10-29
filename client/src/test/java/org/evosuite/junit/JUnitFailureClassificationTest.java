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
package org.evosuite.junit;

import org.evosuite.junit.JUnitFailurePolicy.Verdict;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.junit.jupiter.api.Test;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The checks of a suite and the order-sensitivity analysis share one reading of a failure.
 */
public class JUnitFailureClassificationTest {

    private static Verdict classify(JUnitResult result, JUnitFailure failure, TestCase test) {
        return JUnitFailurePolicy.classify(result, failure, test, new Class<?>[0], new AtomicBoolean(false));
    }

    private static Verdict classify(JUnitFailure failure) {
        return classify(new JUnitResult(false, 1, 1), failure, null);
    }

    @Test
    public void failuresAreSortedByTheFirstExplanationThatApplies() {
        assertEquals(Verdict.SUITE_FAILURE, classify(
                new JUnitFailure("m", "java.lang.IllegalStateException", null, false, "")));
        assertEquals(Verdict.AGENT_ATTACH, classify(new JUnitFailure("Failed to attach Java Agent",
                "java.lang.IllegalStateException", "initializationError", false, "")));
        assertEquals(Verdict.RESOURCE_LIMIT, classify(new JUnitFailure("m",
                "org.evosuite.runtime.TooManyResourcesException", "test0", false, "")));
        assertEquals(Verdict.TIMEOUT, classify(new JUnitFailure("m",
                "java.util.concurrent.TimeoutException", "test0", false, "")));
        assertEquals(Verdict.ENVIRONMENT, classify(new JUnitFailure("m",
                "java.awt.HeadlessException", "test0", false, "")));
        assertEquals(Verdict.UNSTABLE, classify(new JUnitFailure("m",
                "java.lang.IllegalStateException", "test0", false, "")));
    }

    @Test
    public void aFailureOfATestTheSearchRecordedAsFailingIsExpected() {
        TestCase failing = new DefaultTestCase();
        failing.setFailing();
        JUnitFailure failure = new JUnitFailure("m", "java.lang.IllegalStateException", "test0", false, "");

        assertEquals(Verdict.EXPECTED, classify(new JUnitResult(false, 1, 1), failure, failing));
        assertEquals(Verdict.UNSTABLE, classify(new JUnitResult(false, 1, 1), failure, new DefaultTestCase()));
    }

    @Test
    public void aJunit4TimeoutIsJudgedLikeAJunit5One() {
        Failure junit4 = new Failure(Description.createTestDescription("T", "test0"),
                new java.util.concurrent.TimeoutException("timed out"));

        assertEquals(Verdict.TIMEOUT, classify(JUnitResultBuilder.toFailure(junit4)));
    }
}
