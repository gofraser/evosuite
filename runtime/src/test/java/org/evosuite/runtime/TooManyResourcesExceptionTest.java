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

import org.evosuite.runtime.vnet.NonFunctionalRequirementExtension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TooManyResourcesExceptionTest {

    private static final TooManyResourcesException LIMIT =
            new TooManyResourcesException("Loop has been executed more times than the allowed 10000");

    @Test
    public void theLimitItselfAndAWrapperWithItAsCauseAreDetected() {
        assertTrue(TooManyResourcesException.isOrWraps(LIMIT));
        assertTrue(TooManyResourcesException.isOrWraps(new IllegalStateException("wrapped", LIMIT)));
    }

    /**
     * A persistence layer that rethrows {@code new PersistenceException(e.toString())} keeps no
     * cause: the exception that reaches the test only names the limit in its message.
     */
    @Test
    public void aWrapperThatOnlyQuotesTheLimitInItsMessageIsDetected() {
        Exception wrapper = new IllegalStateException(LIMIT.toString());

        assertTrue(TooManyResourcesException.isOrWraps(wrapper));
        assertTrue(TooManyResourcesException.isOrWraps(new RuntimeException("outer", wrapper)));
    }

    @Test
    public void unrelatedExceptionsAreNotDetected() {
        assertFalse(TooManyResourcesException.isOrWraps(null));
        assertFalse(TooManyResourcesException.isOrWraps(new IllegalStateException()));
        assertFalse(TooManyResourcesException.isOrWraps(new IllegalStateException("too many resources")));
        assertFalse(TooManyResourcesException.isOrWraps(new RuntimeException("a", new RuntimeException("b"))));
    }

    /**
     * Jackson's JsonMappingException builds its message by walking its reference path; a cyclic
     * path exhausts the loop limit inside getMessage(), and EvoSuite asks while rendering the test.
     */
    @Test
    public void aMessageOrCauseThatThrowsIsNoMatch() {
        Exception throwingMessage = new IllegalStateException() {
            @Override
            public String getMessage() {
                throw new TooManyResourcesException("Loop has been executed more times than the allowed 10000");
            }
        };
        Exception throwingCause = new IllegalStateException("plain") {
            @Override
            public synchronized Throwable getCause() {
                throw new NullPointerException();
            }
        };

        assertFalse(TooManyResourcesException.isOrWraps(throwingMessage));
        assertFalse(TooManyResourcesException.isOrWraps(throwingCause));
        assertTrue(TooManyResourcesException.isOrWraps(new RuntimeException(LIMIT.toString(), throwingMessage)));
        assertTrue(TooManyResourcesException.isOrWraps(new RuntimeException("outer", new IllegalStateException("x", LIMIT) {
            @Override
            public String getMessage() {
                throw new NullPointerException();
            }
        })));
    }

    @Test
    public void theExtensionSwallowsAQuotedLimitAndRethrowsOtherExceptions() throws Throwable {
        NonFunctionalRequirementExtension extension = new NonFunctionalRequirementExtension();

        extension.handleTestExecutionException(null, new IllegalStateException(LIMIT.toString()));

        IllegalStateException other = new IllegalStateException("something else");
        Throwable thrown = assertThrows(IllegalStateException.class,
                () -> extension.handleTestExecutionException(null, other));
        assertSame(other, thrown);
    }
}
