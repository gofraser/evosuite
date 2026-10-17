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
 * The statements of a test at one point in time, so that a failed insertion can be undone.
 *
 * <p>Recursive construction may insert dependencies before or after the requested position, and
 * nested helpers may append at the end of the test, so neither a positional range nor the tail
 * reliably identifies the statements an attempt added. A checkpoint therefore records the
 * statement objects themselves, and {@link #rollback()} removes exactly those that were not part of
 * the test when the checkpoint was taken, wherever they were inserted.
 *
 * <p>A rollback cannot undo changes made in place to statements that existed at the checkpoint.
 *
 * @author Gordon Fraser
 */
public final class StatementCheckpoint {

    private static final Logger logger = LoggerFactory.getLogger(StatementCheckpoint.class);

    private final TestCase test;
    private final Set<Statement> originalStatements;

    private StatementCheckpoint(TestCase test) {
        this.test = test;
        this.originalStatements = Collections.newSetFromMap(new IdentityHashMap<Statement, Boolean>());
        for (Statement statement : test) {
            originalStatements.add(statement);
        }
    }

    /**
     * Records the statements currently in the test.
     *
     * @param test the test that is about to be extended
     * @return a checkpoint for {@code test}
     */
    public static StatementCheckpoint of(TestCase test) {
        return new StatementCheckpoint(test);
    }

    /**
     * Removes all statements added since this checkpoint was taken. The removal runs in reverse
     * test order, so a statement disappears before the statements it depends on.
     */
    public void rollback() {
        int removed = 0;
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
        logger.debug("Rolled back {} inserted statements", removed);
    }
}
