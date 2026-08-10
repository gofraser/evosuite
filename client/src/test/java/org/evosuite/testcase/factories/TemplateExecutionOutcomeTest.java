/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
 */
package org.evosuite.testcase.factories;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateExecutionOutcomeTest {

    @Test
    void acceptsOnlyAReachedEntryWithoutTimeoutOrException() {
        TestCase test = mock(TestCase.class);
        EntityWithParametersStatement entry = mock(EntityWithParametersStatement.class);
        when(test.findTargetExecutableStatement()).thenReturn(entry);
        when(entry.getPosition()).thenReturn(0);

        ExecutionResult successful = new ExecutionResult(test);
        successful.setExecutedStatements(1);
        assertTrue(TemplateExecutionOutcome.from(test, successful, 0.0).reachedEntry());

        ExecutionResult throwing = new ExecutionResult(test);
        throwing.setExecutedStatements(1);
        throwing.reportNewThrownException(0, new IllegalStateException("template failed"));
        assertFalse(TemplateExecutionOutcome.from(test, throwing, 0.0).reachedEntry());
    }
}
