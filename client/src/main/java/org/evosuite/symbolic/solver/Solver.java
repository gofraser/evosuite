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

import org.evosuite.symbolic.expr.Constraint;
import org.evosuite.symbolic.expr.Variable;
import org.evosuite.symbolic.expr.bv.IntegerVariable;
import org.evosuite.symbolic.expr.constraint.ConstraintEvaluator;
import org.evosuite.symbolic.expr.fp.RealVariable;
import org.evosuite.symbolic.expr.ref.ClassReferenceVariable;
import org.evosuite.symbolic.expr.ref.array.ArrayVariable;
import org.evosuite.symbolic.expr.str.StringVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.*;

/**
 * Interface for SMT solvers.
 *
 * @author Gordon Fraser
 */
public abstract class Solver {

    private final boolean addMissingVariables;
    private final SolverCache solverCache;

    /**
     * Constructs a solver.
     *
     * @param addMissingVariables whether to add missing variables
     */
    public Solver(boolean addMissingVariables) {
        this(addMissingVariables, SolverCache.getInstance());
    }

    /**
     * Constructs a solver with default settings.
     */
    public Solver() {
        //TODO: Replace the getInstance methods with a dependency injection framework later on (e.g guice).
        this(false, SolverCache.getInstance());
    }

    /**
     * Constructs a solver with the provided settings and cache.
     *
     * @param addMissingVariables whether to add missing variables
     * @param solverCache         the solver cache to use
     */
    public Solver(boolean addMissingVariables, SolverCache solverCache) {
        this.addMissingVariables = addMissingVariables;
        this.solverCache = solverCache;
    }

    static Logger logger = LoggerFactory.getLogger(Solver.class);

    /**
     * Solves a collection of constraints.
     *
     * @param constraints a constraint system to be solved
     * @return a non-null result that is SAT or UNSAT
     * @throws SolverTimeoutException    a timeout occurred while executing the solver
     * @throws IOException               an IOException occurred while executing the solver
     * @throws SolverParseException      the solver's result could not be parsed into a valid SolverResult
     * @throws SolverEmptyQueryException the solver query was empty
     * @throws SolverErrorException      the solver reported an error after its execution
     */
    public SolverResult solve(Collection<Constraint<?>> constraints)
            throws SolverTimeoutException, SolverParseException, SolverEmptyQueryException,
            SolverErrorException, IOException {
        if (solverCache.hasCachedResult(constraints)) {
            return solverCache.getCachedResult();
        }

        SolverResult solverResult;
        try {
            solverResult = executeSolver(constraints);

            if (solverResult != null && !solverResult.isUnknown()) {
                solverCache.saveSolverResult(constraints, solverResult);
            }
        } catch (IllegalArgumentException | IOException e) {
            solverResult = null;
        }

        return solverResult;
    }

    /**
     * Executes the actual solver implementation.
     *
     * @param constraints the collection of constraints
     * @return the solver result
     * @throws SolverTimeoutException    if a timeout occurs
     * @throws IOException               if an I/O error occurs
     * @throws SolverParseException      if the result cannot be parsed
     * @throws SolverEmptyQueryException if the query is empty
     * @throws SolverErrorException      if the solver reports an error
     */
    public abstract SolverResult executeSolver(Collection<Constraint<?>> constraints)
            throws SolverTimeoutException, IOException, SolverParseException,
            SolverEmptyQueryException, SolverErrorException;

    protected boolean addMissingVariables() {
        return addMissingVariables;
    }

    /**
     * Returns a mapping from variables to their current concrete values.
     *
     * @param variables the set of variables
     * @return a mapping from variables to their current concrete values.
     */
    protected static Map<String, Object> getConcreteValues(Set<Variable<?>> variables) {

        Map<String, Object> concreteValues = new HashMap<>();
        for (Variable<?> v : variables) {
            String varName = v.getName();
            Object concreteValue = v.getConcreteValue();
            concreteValues.put(varName, concreteValue);
        }
        return concreteValues;
    }

    /**
     * Creates a set with all the variables in the constraints.
     *
     * @param constraints the constraint system
     * @return the set of variables in the constraint system
     */
    protected static Set<Variable<?>> getVariables(Collection<Constraint<?>> constraints) {
        Set<Variable<?>> variables = new HashSet<>();
        for (Constraint<?> c : constraints) {
            variables.addAll(c.getLeftOperand().getVariables());
            variables.addAll(c.getRightOperand().getVariables());
        }
        return variables;
    }

    /**
     * Restore all concrete values of the variables using the concreteValues mapping.
     *
     * @param variables      the set of variables
     * @param concreteValues the mapping of variable names to concrete values
     */
    protected static void setConcreteValues(Set<Variable<?>> variables, Map<String, Object> concreteValues) {
        for (Variable<?> v : variables) {

            String varName = v.getName();

            if (!concreteValues.containsKey(varName)) {
                continue;
            }

            Object concreteValue = concreteValues.get(varName);

            if (v instanceof StringVariable) {
                StringVariable sv = (StringVariable) v;
                String concreteString = (String) concreteValue;
                sv.setConcreteValue(concreteString);
            } else if (v instanceof IntegerVariable) {
                IntegerVariable iv = (IntegerVariable) v;
                Long concreteInteger = (Long) concreteValue;
                iv.setConcreteValue(concreteInteger);
            } else if (v instanceof RealVariable) {
                RealVariable ir = (RealVariable) v;
                Double concreteReal = (Double) concreteValue;
                ir.setConcreteValue(concreteReal);
            } else if (v instanceof ArrayVariable) {
                ArrayVariable arr = (ArrayVariable) v;
                arr.setConcreteValue(
                        getResizedArray(
                                arr.getConcreteValue(),
                                concreteValue));
            } else if (v instanceof ClassReferenceVariable) {
                ClassReferenceVariable rv = (ClassReferenceVariable) v;
                rv.initializeReference(concreteValue);
            } else {
                logger.warn("unknow variable type " + v.getClass().getName());
            }
        }
    }

    /**
     * Checks if the constraints are SAT using the provided solver result.
     *
     * @param constraints the collection of constraints
     * @param satResult   the solver result
     * @return true if the constraints are SAT, false otherwise
     */
    protected static boolean checkSAT(Collection<Constraint<?>> constraints, SolverResult satResult) {

        if (satResult == null) {
            throw new NullPointerException("satResult should be non-null");
        }

        if (!satResult.isSAT()) {
            throw new IllegalArgumentException("satResult should be SAT");
        }

        // back-up values
        Set<Variable<?>> variables = getVariables(constraints);
        Map<String, Object> initialValues = getConcreteValues(variables);
        // set new values
        Map<String, Object> newValues = satResult.getModel();
        setConcreteValues(variables, newValues);

        try {
            // check SAT with new values
            ConstraintEvaluator evaluator = new ConstraintEvaluator();
            for (Constraint<?> constraint : constraints) {
                Boolean evaluation = (Boolean) constraint.accept(evaluator, null);
                if (evaluation == null) {
                    throw new NullPointerException();
                }
                if (evaluation == false) {
                    return false;
                }
            }
            return true;
        } finally {
            // restore values
            setConcreteValues(variables, initialValues);
        }
    }

    /**
     * Returns a new array with the longest length.
     * <p>
     * TODO: Rework this in the future, the way we talk about lengths is probably not the best.
     * </p>
     *
     * @param originalArray the original array
     * @param newArray      the new array
     * @return the resized array
     */
    private static Object getResizedArray(Object originalArray, Object newArray) {
        int originalLength = Array.getLength(originalArray);
        int newLength = Array.getLength(newArray);

        if (originalLength > newLength) {
            Object copyArr = Array.newInstance(newArray.getClass().getComponentType(), originalLength);
            System.arraycopy(newArray, 0, copyArr, 0, Math.min(originalLength, newLength));
            return copyArr;
        }

        return newArray;
    }

}
