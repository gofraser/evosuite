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
package org.evosuite.junit;

import org.evosuite.testcase.execution.ExecutionTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * The information from executing a JUnit test case.
 *
 * @author galeotti
 * @author José Campos
 */
public class JUnitResult {


    private String name;


    private boolean successful;


    private long runtime;


    private String trace;


    private ExecutionTrace executionTrace;


    private int failureCount;


    private int runCount;


    private final ArrayList<JUnitFailure> junitFailures = new ArrayList<>();


    private Class<?> junitClass;


    /**
     * Initializes a new JUnitResult with the given name.
     *
     * @param name the name of the test
     */
    public JUnitResult(String name) {
        this.successful = true;
        this.name = name;
        this.failureCount = 0;
        this.runCount = 0;
    }


    /**
     * Initializes a new JUnitResult with the given name and JUnit class.
     *
     * @param name the name of the test
     * @param junitClass the JUnit class
     */
    public JUnitResult(String name, Class<?> junitClass) {
        this.successful = true;
        this.name = name;
        this.failureCount = 0;
        this.runCount = 0;
        this.junitClass = junitClass;
    }

    /**
     * Constructor.
     *
     * @param wasSuccessful whether the test was successful
     * @param failureCount number of failures
     * @param runCount number of runs
     */
    public JUnitResult(boolean wasSuccessful, int failureCount, int runCount) {
        this.successful = wasSuccessful;
        this.failureCount = failureCount;
        this.runCount = runCount;
    }

    /**
     * Getter for name.
     *
     * @return the name
     */
    public String getName() {
        return this.name;
    }

    /**
     * Setter for name.
     *
     * @param n the name to set
     */
    public void setName(String n) {
        this.name = n;
    }

    /**
     * Checks if successful.
     *
     * @return true if successful
     */
    public boolean wasSuccessful() {
        return this.successful;
    }

    /**
     * Sets success status.
     *
     * @param s success status
     */
    public void setSuccessful(boolean s) {
        this.successful = s;
    }

    /**
     * Getter for runtime.
     *
     * @return the runtime
     */
    public long getRuntime() {
        return this.runtime;
    }

    /**
     * Setter for runtime.
     *
     * @param r the runtime to set
     */
    public void setRuntime(long r) {
        this.runtime = r;
    }

    /**
     * Getter for trace.
     *
     * @return the trace
     */
    public String getTrace() {
        return this.trace;
    }

    /**
     * Setter for trace.
     *
     * @param t the trace to set
     */
    public void setTrace(String t) {
        this.trace = t;
    }

    /**
     * Getter for execution trace.
     *
     * @return the execution trace
     */
    public ExecutionTrace getExecutionTrace() {
        return this.executionTrace;
    }

    /**
     * Setter for execution trace.
     *
     * @param et the execution trace to set
     */
    public void setExecutionTrace(ExecutionTrace et) {
        this.executionTrace = et;
    }

    /**
     * Getter for failure count.
     *
     * @return the failure count
     */
    public int getFailureCount() {
        return this.failureCount;
    }

    public void incrementFailureCount() {
        this.failureCount++;
    }

    /**
     * Getter for run count.
     *
     * @return the run count
     */
    public int getRunCount() {
        return runCount;
    }

    public void incrementRunCount() {
        this.runCount++;
    }

    /**
     * Getter for failures.
     *
     * @return the list of failures
     */
    public List<JUnitFailure> getFailures() {
        return junitFailures;
    }

    /**
     * Adds a failure.
     *
     * @param junitFailure the failure to add
     */
    public void addFailure(JUnitFailure junitFailure) {
        junitFailures.add(junitFailure);
    }

    public Class<?> getJUnitClass() {
        return this.junitClass;
    }


    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + this.failureCount;
        result = prime * result
                + ((this.junitFailures == null) ? 0 : this.junitFailures.hashCode());
        result = prime * result + this.runCount;
        result = prime * result + (this.successful ? 1231 : 1237);
        return result;
    }


    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        JUnitResult other = (JUnitResult) obj;
        if (this.failureCount != other.failureCount) {
            return false;
        }
        if (this.junitFailures == null) {
            if (other.junitFailures != null) {
                return false;
            }
        } else if (!this.junitFailures.equals(other.junitFailures)) {
            return false;
        }
        if (this.runCount != other.runCount) {
            return false;
        }
        return this.successful == other.successful;
    }
}
