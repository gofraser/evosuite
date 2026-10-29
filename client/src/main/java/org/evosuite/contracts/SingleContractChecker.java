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
package org.evosuite.contracts;

import org.evosuite.testcase.execution.ExecutionObserver;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * SingleContractChecker class.
 * </p>
 *
 * @author fraser
 */
public class SingleContractChecker extends ExecutionObserver {

    private final Contract contract;

    private static final Logger logger = LoggerFactory.getLogger(SingleContractChecker.class);

    private boolean valid = true;

    public SingleContractChecker(Contract contract) {
        this.contract = contract;
    }

    public boolean isValid() {
        return valid;
    }

    @Override
    public void afterStatement(Statement statement, Scope scope,
                               Throwable exception) {
        try {
            logger.debug("Checking contract " + contract);
            if (contract.check(statement, scope, exception) != null) {
                //FailingTestSet.addFailingTest(currentTest, contract, statement, exception);
                valid = false;
            }
        } catch (Throwable t) {
            logger.info("Caught exception during contract checking: " + t);
        }
    }

}
