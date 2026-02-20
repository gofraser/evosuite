/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper to manage atomic statement insertions and rollbacks.
 *
 * @author Gordon Fraser
 */
class StatementInserter {

    private static final Logger logger = LoggerFactory.getLogger(StatementInserter.class);

    private final TestCase test;
    private final int startPosition;
    private final int originalSize;

    StatementInserter(TestCase test, int position) {
        this.test = test;
        this.startPosition = position;
        this.originalSize = test.size();
    }

    /**
     * Remove all statements added since this inserter was created.
     */
    void rollback() {
        int currentSize = test.size();
        int added = currentSize - originalSize;
        if (added <= 0) {
            return;
        }

        logger.debug("Rolling back {} statements starting at {}", added, startPosition);

        // Remove in reverse order to maintain consistency
        for (int i = added - 1; i >= 0; i--) {
            int posToRemove = startPosition + i;
            if (posToRemove < test.size()) {
                if (logger.isTraceEnabled()) {
                    logger.trace("  Removing statement: {}", test.getStatement(posToRemove).getCode());
                }
                test.remove(posToRemove);
            }
        }
    }
}
