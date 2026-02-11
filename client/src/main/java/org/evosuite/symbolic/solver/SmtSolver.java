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
package org.evosuite.symbolic.solver;

import org.apache.commons.exec.ExecuteException;
import org.evosuite.utils.ProcessLauncher;
import org.evosuite.utils.ProcessTimeoutException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Abstract base class for SMT solvers.
 */
public abstract class SmtSolver extends Solver {

    public SmtSolver(boolean addMissingVariables) {
        super(addMissingVariables);
    }

    public SmtSolver() {
        super();
    }

    /**
     * Launches a new solving process.
     *
     * @param solverCmd   the solver command
     * @param smtQueryStr the SMT query string
     * @param hardTimeout the hard timeout in milliseconds
     * @param stdout      the output stream for the solver's output
     * @throws IOException            if an I/O error occurs
     * @throws SolverTimeoutException if the solver times out
     * @throws SolverErrorException   if the solver reports an error
     */
    protected static void launchNewSolvingProcess(String solverCmd, String smtQueryStr, int hardTimeout,
                                                  OutputStream stdout)
            throws IOException, SolverTimeoutException, SolverErrorException {

        ByteArrayInputStream input = new ByteArrayInputStream(smtQueryStr.getBytes());

        ProcessLauncher launcher = new ProcessLauncher(stdout, input);

        long solverStartTimeMillis = System.currentTimeMillis();
        try {
            int exitCode = launcher.launchNewProcess(solverCmd, hardTimeout);

            if (exitCode == 0) {
                logger.debug("Solver execution finished normally");
                return;
            } else {
                String errMsg = String.format("Solver execution finished abnormally with exit code {}", exitCode);
                logger.debug(errMsg);
                throw new SolverErrorException(errMsg);
            }
        } catch (ExecuteException ex) {
            logger.debug("Solver subprocesses failed");
            throw new SolverErrorException("Solver subprocesses failed");

        } catch (ProcessTimeoutException ex) {
            logger.debug("Solver stopped due to solver timeout");
            throw new SolverTimeoutException();

        } finally {
            long solverEndTimeMillis = System.currentTimeMillis();
            long solverDurationSecs = (solverEndTimeMillis - solverStartTimeMillis) / 1000;
            logger.debug("Solver execution time was {}s", solverDurationSecs);
        }

    }

}
