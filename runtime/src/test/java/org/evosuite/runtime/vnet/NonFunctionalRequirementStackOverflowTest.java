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
package org.evosuite.runtime.vnet;

import org.evosuite.runtime.annotation.EvoSuiteEndsOnResourceLimit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unbounded recursion ends on the stack in a cold JVM and on a resource limit in a warm one, so
 * a stack overflow is accepted from a test marked as ending on a limit, and from no other.
 */
public class NonFunctionalRequirementStackOverflowTest {

    @EvoSuiteEndsOnResourceLimit
    public void marked() {
    }

    public void unmarked() {
    }

    private static Description description(String method) throws Exception {
        Method m = NonFunctionalRequirementStackOverflowTest.class.getMethod(method);
        return Description.createTestDescription(NonFunctionalRequirementStackOverflowTest.class, method,
                m.getAnnotations());
    }

    private static ExtensionContext context(String method) throws Exception {
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getTestMethod()).thenReturn(
                Optional.of(NonFunctionalRequirementStackOverflowTest.class.getMethod(method)));
        return context;
    }

    private static Statement throwing(Throwable t) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                throw t;
            }
        };
    }

    private static Throwable wrapped() {
        return new RuntimeException("outer", new IllegalStateException("middle", new StackOverflowError()));
    }

    @Test
    public void ruleSwallowsAStackOverflowOfAMarkedTest() throws Throwable {
        NonFunctionalRequirementRule rule = new NonFunctionalRequirementRule();
        rule.apply(throwing(new StackOverflowError()), description("marked")).evaluate();
        rule.apply(throwing(wrapped()), description("marked")).evaluate();
    }

    @Test
    public void ruleRethrowsAStackOverflowOfAnUnmarkedTest() throws Throwable {
        NonFunctionalRequirementRule rule = new NonFunctionalRequirementRule();
        StackOverflowError error = new StackOverflowError();
        assertSame(error, assertThrows(StackOverflowError.class,
                () -> rule.apply(throwing(error), description("unmarked")).evaluate()));
        Throwable wrapped = wrapped();
        assertSame(wrapped, assertThrows(RuntimeException.class,
                () -> rule.apply(throwing(wrapped), description("unmarked")).evaluate()));
    }

    @Test
    public void ruleStillRethrowsOtherFailuresOfAMarkedTest() {
        NonFunctionalRequirementRule rule = new NonFunctionalRequirementRule();
        assertThrows(IllegalStateException.class,
                () -> rule.apply(throwing(new IllegalStateException()), description("marked")).evaluate());
    }

    @Test
    public void extensionSwallowsAStackOverflowOfAMarkedTest() throws Throwable {
        NonFunctionalRequirementExtension extension = new NonFunctionalRequirementExtension();
        extension.handleTestExecutionException(context("marked"), new StackOverflowError());
        extension.handleTestExecutionException(context("marked"), wrapped());
    }

    @Test
    public void extensionRethrowsAStackOverflowOfAnUnmarkedTest() {
        NonFunctionalRequirementExtension extension = new NonFunctionalRequirementExtension();
        StackOverflowError error = new StackOverflowError();
        assertSame(error, assertThrows(StackOverflowError.class,
                () -> extension.handleTestExecutionException(context("unmarked"), error)));
        Throwable wrapped = wrapped();
        assertSame(wrapped, assertThrows(RuntimeException.class,
                () -> extension.handleTestExecutionException(context("unmarked"), wrapped)));
        assertThrows(StackOverflowError.class,
                () -> extension.handleTestExecutionException(null, new StackOverflowError()));
    }
}
