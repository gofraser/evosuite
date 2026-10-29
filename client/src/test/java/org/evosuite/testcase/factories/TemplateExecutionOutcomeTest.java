/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
 */
package org.evosuite.testcase.factories;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateExecutionOutcomeTest {

    @AfterEach
    void clearTargetContext() {
        TargetContext.clearActive();
    }

    @Test
    void acceptsOnlyAReachedEntryWithoutTimeoutOrException() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference argument = test.addStatement(new StringPrimitiveStatement(test, "value"));
        GenericConstructor constructor = new GenericConstructor(
                StringBuilder.class.getConstructor(String.class), StringBuilder.class);
        test.addStatement(new ConstructorStatement(
                test, constructor, Collections.singletonList(argument)));
        TargetContext.setActive(new TargetContext(
                StringBuilder.class.getName(), constructor.getNameWithDescriptor(), null, true));

        ExecutionResult successful = new ExecutionResult(test);
        successful.setExecutedStatements(2);
        assertTrue(TemplateExecutionOutcome.from(test, successful, 0.0).reachedEntry());

        ExecutionResult throwing = new ExecutionResult(test);
        throwing.setExecutedStatements(2);
        throwing.reportNewThrownException(0, new IllegalStateException("template failed"));
        assertFalse(TemplateExecutionOutcome.from(test, throwing, 0.0).reachedEntry());
    }
}
