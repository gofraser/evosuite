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
package org.evosuite.llm.search;

import org.evosuite.testcase.ConstraintHelper;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.NullReference;
import org.evosuite.testcase.variable.VariableReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Derives a coarse direct-invocation context for method calls so exception barriers
 * can distinguish masked null/non-null regimes for the same method.
 */
final class InvocationContextClassifier {

    static final InvocationContext DEFAULT_CONTEXT =
            new InvocationContext("all", "any observed argument state");

    private InvocationContextClassifier() {
        // utility
    }

    static InvocationContext classify(MethodStatement statement) {
        if (statement == null) {
            return DEFAULT_CONTEXT;
        }
        List<VariableReference> parameters = safeParameters(statement);
        if (parameters.isEmpty()) {
            return DEFAULT_CONTEXT;
        }

        Class<?>[] declaredTypes = declaredParameterTypes(statement);
        List<String> keyParts = new ArrayList<>();
        List<String> labelParts = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            VariableReference parameter = parameters.get(i);
            Class<?> declaredType = i < declaredTypes.length ? declaredTypes[i] : null;
            if (!isReferenceParameter(declaredType, parameter)) {
                continue;
            }
            String state = isNull(parameter, statement) ? "null" : "nonnull";
            keyParts.add("arg" + i + "=" + state);
            labelParts.add("arg" + i + " (" + parameterTypeLabel(declaredType, parameter) + ")=" + state);
        }
        if (keyParts.isEmpty()) {
            return DEFAULT_CONTEXT;
        }
        return new InvocationContext(String.join(",", keyParts), String.join(", ", labelParts));
    }

    private static List<VariableReference> safeParameters(MethodStatement statement) {
        try {
            List<VariableReference> parameters = statement.getParameterReferences();
            return parameters == null ? Collections.emptyList() : parameters;
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private static Class<?>[] declaredParameterTypes(MethodStatement statement) {
        try {
            if (statement.getMethod() == null || statement.getMethod().getMethod() == null) {
                return new Class<?>[0];
            }
            return statement.getMethod().getMethod().getParameterTypes();
        } catch (RuntimeException e) {
            return new Class<?>[0];
        }
    }

    private static boolean isReferenceParameter(Class<?> declaredType, VariableReference parameter) {
        if (declaredType != null) {
            return !declaredType.isPrimitive();
        }
        if (parameter == null) {
            return true;
        }
        try {
            return !parameter.isPrimitive();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean isNull(VariableReference parameter, MethodStatement statement) {
        if (parameter == null || parameter instanceof NullReference) {
            return true;
        }
        TestCase testCase = null;
        try {
            testCase = parameter.getTestCase();
        } catch (RuntimeException ignored) {
            // fall through
        }
        if (testCase == null) {
            try {
                testCase = statement.getTestCase();
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        if (testCase == null) {
            return false;
        }
        try {
            return ConstraintHelper.isNull(parameter, testCase);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String parameterTypeLabel(Class<?> declaredType, VariableReference parameter) {
        if (declaredType != null) {
            String simpleName = declaredType.getSimpleName();
            return simpleName == null || simpleName.isEmpty() ? declaredType.getName() : simpleName;
        }
        if (parameter != null) {
            try {
                String simpleName = parameter.getSimpleClassName();
                if (simpleName != null && !simpleName.isEmpty()) {
                    return simpleName;
                }
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        return "Object";
    }

    static final class InvocationContext {
        private static final String DEFAULT_KEY = "all";
        private static final String DEFAULT_LABEL = "any observed argument state";
        private final String key;
        private final String label;

        InvocationContext(String key, String label) {
            this.key = key == null || key.isEmpty() ? DEFAULT_KEY : key;
            this.label = label == null || label.isEmpty() ? DEFAULT_LABEL : label;
        }

        String getKey() {
            return key;
        }

        String getLabel() {
            return label;
        }
    }
}
