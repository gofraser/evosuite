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
package org.evosuite.llm.postprocess;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Position-based stable IDs for one finalized test case.
 */
public final class LlmPostProcessingReferences {

    private final Map<String, Integer> statementPositions;
    private final Map<String, Integer> variablePositions;
    private final Set<String> statementIds;
    private final Set<String> variableIds;

    private LlmPostProcessingReferences(Map<String, Integer> statementPositions,
                                        Map<String, Integer> variablePositions) {
        this.statementPositions = Collections.unmodifiableMap(new LinkedHashMap<>(statementPositions));
        this.variablePositions = Collections.unmodifiableMap(new LinkedHashMap<>(variablePositions));
        this.statementIds = this.statementPositions.keySet();
        this.variableIds = this.variablePositions.keySet();
    }

    /**
     * Assigns statement ids and variable ids to the statements of a test, by position.
     *
     * @param test the finalized test case, may be null
     * @return the references of the test; empty if the test is null
     */
    public static LlmPostProcessingReferences from(TestCase test) {
        Map<String, Integer> statementPositions = new LinkedHashMap<>();
        Map<String, Integer> variablePositions = new LinkedHashMap<>();
        if (test == null) {
            return new LlmPostProcessingReferences(statementPositions, variablePositions);
        }

        for (int position = 0; position < test.size(); position++) {
            Statement statement = test.getStatement(position);
            statementPositions.put(statementId(position), position);
            if (isNameableReturnValue(statement, position)) {
                variablePositions.put(variableId(position), position);
            }
        }
        return new LlmPostProcessingReferences(statementPositions, variablePositions);
    }

    public Set<String> getStatementIds() {
        return statementIds;
    }

    public Set<String> getVariableIds() {
        return variableIds;
    }

    public Map<String, Integer> getStatementPositions() {
        return statementPositions;
    }

    public Map<String, Integer> getVariablePositions() {
        return variablePositions;
    }

    public boolean hasStatementId(String statementId) {
        return statementPositions.containsKey(statementId);
    }

    public boolean hasVariableId(String variableId) {
        return variablePositions.containsKey(variableId);
    }

    /**
     * Returns the position of a statement.
     *
     * @param statementId the id of the statement
     * @return the position of the statement in the test
     * @throws IllegalArgumentException if the id is unknown
     */
    public int getStatementPosition(String statementId) {
        Integer position = statementPositions.get(statementId);
        if (position == null) {
            throw new IllegalArgumentException("Unknown statement ID: " + statementId);
        }
        return position;
    }

    /**
     * Returns the position of the statement that defines a variable.
     *
     * @param variableId the id of the variable
     * @return the position of the defining statement in the test
     * @throws IllegalArgumentException if the id is unknown
     */
    public int getVariablePosition(String variableId) {
        Integer position = variablePositions.get(variableId);
        if (position == null) {
            throw new IllegalArgumentException("Unknown variable ID: " + variableId);
        }
        return position;
    }

    /**
     * Looks up the variable that an id denotes in a test.
     *
     * @param test the test to resolve the id in
     * @param variableId the id of the variable
     * @return the return value of the statement at the position of the id
     * @throws IllegalArgumentException if the id is unknown or no longer denotes a nameable return value in the test
     */
    public VariableReference resolveVariable(TestCase test, String variableId) {
        int position = getVariablePosition(variableId);
        if (test == null || position < 0 || position >= test.size()) {
            throw new IllegalArgumentException("Variable ID cannot be resolved in target test: " + variableId);
        }
        Statement statement = test.getStatement(position);
        if (!isNameableReturnValue(statement, position)) {
            throw new IllegalArgumentException(
                    "Variable ID no longer points to a nameable return value: " + variableId);
        }
        return statement.getReturnValue();
    }

    LlmPostProcessingResponseParser.ParseContext toParseContext(PostProcessingOptions options) {
        return LlmPostProcessingResponseParser.context(
                getStatementIds(), getVariableIds(),
                Collections.<LlmPostProcessingResponseParser.CallableMethod>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String, String>emptyMap(),
                Collections.<String, LlmPostProcessingResponseParser.SelectableCandidate>emptyMap(), options);
    }

    /**
     * Returns the id of the statement at a position.
     *
     * @param position the position of the statement, not negative
     * @return the id of the statement
     * @throws IllegalArgumentException if the position is negative
     */
    public static String statementId(int position) {
        if (position < 0) {
            throw new IllegalArgumentException("Statement position must be non-negative: " + position);
        }
        return "s" + position;
    }

    /**
     * Returns the id of the variable defined at a position.
     *
     * @param position the position of the defining statement, not negative
     * @return the id of the variable
     * @throws IllegalArgumentException if the position is negative
     */
    public static String variableId(int position) {
        if (position < 0) {
            throw new IllegalArgumentException("Variable position must be non-negative: " + position);
        }
        return "v" + position;
    }

    /**
     * Tells whether a statement defines a variable that can be given a name: an accessible, non-void value
     * that is neither a field nor an array element and is defined at the expected position.
     *
     * @param statement the statement to check, may be null
     * @param expectedPosition the position of the statement in its test
     * @return true if the return value of the statement can be named
     */
    public static boolean isNameableReturnValue(Statement statement, int expectedPosition) {
        if (statement == null) {
            return false;
        }
        VariableReference returnValue = statement.getReturnValue();
        if (returnValue == null || returnValue.isVoid() || !returnValue.isAccessible()) {
            return false;
        }
        if (returnValue.isFieldReference() || returnValue.isArrayIndex()) {
            return false;
        }
        if (returnValue.getAdditionalVariableReference() != null) {
            return false;
        }
        return returnValue.getStPosition() == expectedPosition;
    }
}
