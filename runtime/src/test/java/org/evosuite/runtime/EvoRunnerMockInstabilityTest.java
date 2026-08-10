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
package org.evosuite.runtime;

import org.junit.jupiter.api.Test;
import org.mockito.exceptions.misusing.UnfinishedStubbingException;
import org.mockito.exceptions.misusing.WrongTypeOfReturnValue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A functional-mock instability surfacing on replay (a Mockito framework
 * exception) must be recognised so EvoRunner can convert it to a skipped
 * assumption, while a genuine failure must not be.
 */
public class EvoRunnerMockInstabilityTest {

    @Test
    public void mockitoMisuseExceptionsAreRecognisedAsInstability() {
        assertTrue(EvoRunner.isFunctionalMockInstability(
                new UnfinishedStubbingException("unfinished")));
        assertTrue(EvoRunner.isFunctionalMockInstability(
                new WrongTypeOfReturnValue("null cannot be returned")));
    }

    @Test
    public void mockitoExceptionWrappedAsCauseIsRecognised() {
        Throwable wrapped = new RuntimeException("in the SUT",
                new WrongTypeOfReturnValue("null for long"));
        assertTrue(EvoRunner.isFunctionalMockInstability(wrapped));
    }

    @Test
    public void realFailuresAreNotTreatedAsInstability() {
        assertFalse(EvoRunner.isFunctionalMockInstability(new NullPointerException()));
        assertFalse(EvoRunner.isFunctionalMockInstability(new AssertionError("2 != 3")));
        assertFalse(EvoRunner.isFunctionalMockInstability(
                new IllegalArgumentException("bad arg")));
    }

    @Test
    public void selfReferencingCauseDoesNotLoop() {
        RuntimeException selfCause = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(EvoRunner.isFunctionalMockInstability(selfCause));
    }
}
