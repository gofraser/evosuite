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
package org.evosuite.testcase;

import org.evosuite.testcase.statements.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Helper to manage atomic statement insertions and rollbacks.
 *
 * @author Gordon Fraser
 */
class StatementInserter {

    private static final Logger logger = LoggerFactory.getLogger(StatementInserter.class);

    private final TestCase test;
    private final int startPosition;
    private final Set<Statement> originalStatements;

    StatementInserter(TestCase test, int position) {
        this.test = test;
        this.startPosition = position;
        this.originalStatements = Collections.newSetFromMap(new IdentityHashMap<Statement, Boolean>());
        for (Statement statement : test) {
            originalStatements.add(statement);
        }
    }

    /**
     * Remove all statements added since this inserter was created.
     */
    void rollback() {
        int removed = 0;
        // Recursive construction may insert dependencies before or after the requested
        // position, so a positional range does not reliably identify the transaction's
        // statements. Remove precisely the statement objects that did not exist when the
        // insertion started, in reverse test order so their dependencies disappear last.
        for (int position = test.size() - 1; position >= 0; position--) {
            Statement statement = test.getStatement(position);
            if (!originalStatements.contains(statement)) {
                if (logger.isTraceEnabled()) {
                    logger.trace("  Removing inserted statement: {}", statement.getCode());
                }
                test.remove(position);
                removed++;
            }
        }
        logger.debug("Rolled back {} inserted statements for insertion at {}", removed, startPosition);
    }
}
