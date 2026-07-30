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
 */
package org.evosuite.testcase;

import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StatementInserterTest {

    @Test
    void rollbackRemovesOnlyStatementsAddedByTheInsertion() {
        DefaultTestCase test = new DefaultTestCase();
        Statement originalFirst = new StringPrimitiveStatement(test, "first");
        Statement originalSecond = new StringPrimitiveStatement(test, "second");
        test.addStatement(originalFirst);
        test.addStatement(originalSecond);

        StatementInserter inserter = new StatementInserter(test, 1);
        Statement dependencyBefore = new StringPrimitiveStatement(test, "before");
        Statement dependencyAfter = new StringPrimitiveStatement(test, "after");
        test.addStatement(dependencyBefore, 0);
        test.addStatement(dependencyAfter);

        inserter.rollback();

        Assertions.assertEquals(2, test.size());
        Assertions.assertSame(originalFirst, test.getStatement(0));
        Assertions.assertSame(originalSecond, test.getStatement(1));
        Assertions.assertTrue(test.isValid());
    }
}
