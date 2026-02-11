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

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents the result of a solver query.
 *
 * @author galeotti
 */
public class SolverResult implements Serializable {


    private static final long serialVersionUID = -930589471876011035L;

    private enum SolverResultType {
        SAT, UNSAT, UNKNOWN
    }

    private final SolverResultType resultType;

    private final Map<String, Object> model;

    private SolverResult(SolverResultType t, Map<String, Object> model) {
        this.resultType = t;
        this.model = model;
    }

    /**
     * Creates a new UNSAT result.
     *
     * @return a new SolverResult representing UNSAT
     */
    public static SolverResult newUNSAT() {
        return new SolverResult(SolverResultType.UNSAT, null);
    }

    /**
     * Creates a new UNKNOWN result.
     *
     * @return a new SolverResult representing UNKNOWN
     */
    public static SolverResult newUnknown() {
        return new SolverResult(SolverResultType.UNKNOWN, null);
    }

    /**
     * Creates a new SAT result with the provided variable values.
     *
     * @param values a map of variable names to their values
     * @return a new SolverResult representing SAT
     */
    public static SolverResult newSAT(Map<String, Object> values) {
        return new SolverResult(SolverResultType.SAT, values);
    }

    /**
     * Checks if the result is SAT.
     *
     * @return true if the result is SAT, false otherwise
     */
    public boolean isSAT() {
        return resultType.equals(SolverResultType.SAT);
    }

    /**
     * Checks if the model contains a value for the specified variable.
     *
     * @param varName the name of the variable
     * @return true if the model contains the variable, false otherwise
     */
    public boolean containsVariable(String varName) {
        if (!resultType.equals(SolverResultType.SAT)) {
            throw new IllegalStateException("This method should not be called with a non-SAT result");
        }
        return model.containsKey(varName);
    }

    /**
     * Returns the value for the specified variable from the model.
     *
     * @param varName the name of the variable
     * @return the value of the variable, or null if not present
     */
    public Object getValue(String varName) {
        if (!resultType.equals(SolverResultType.SAT)) {
            throw new IllegalStateException("This method should not be called with a non-SAT result");
        }
        return model.get(varName);
    }

    /**
     * Returns the model (variable assignments) as a map.
     *
     * @return a map of variable names to their values
     */
    public Map<String, Object> getModel() {
        HashMap<String, Object> newModel = model == null ? new HashMap<>() : new HashMap<>(model);
        return newModel;
    }

    /**
     * Checks if the result is UNSAT.
     *
     * @return true if the result is UNSAT, false otherwise
     */
    public boolean isUNSAT() {
        return resultType.equals(SolverResultType.UNSAT);

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuffer buff = new StringBuffer();
        buff.append(resultType + "\n");
        if (resultType.equals(SolverResultType.SAT)) {
            buff.append("--------------------" + "\n");
            for (String varName : this.model.keySet()) {
                Object value = this.model.get(varName);
                buff.append(varName + "->" + value + "\n");
            }
        }
        return buff.toString();
    }

    /**
     * Checks if the result is UNKNOWN.
     *
     * @return true if the result is UNKNOWN, false otherwise
     */
    public boolean isUnknown() {
        return resultType.equals(SolverResultType.UNKNOWN);
    }

}
