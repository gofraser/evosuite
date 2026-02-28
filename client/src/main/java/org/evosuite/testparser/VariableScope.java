/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testparser;

import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClass;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maintains a mapping from variable names in source code to EvoSuite VariableReferences.
 * Variables are registered as statements are parsed (each statement produces a VariableReference).
 * Also tracks the fully-resolved GenericClass for each variable, which is needed when the
 * variable is later used as a method callee (to construct GenericMethod with proper type arguments).
 */
public class VariableScope {

    private final Map<String, VariableReference> variables = new LinkedHashMap<>();
    private final Map<String, GenericClass<?>> genericTypes = new LinkedHashMap<>();

    /**
     * Register a variable produced by a statement.
     *
     * @param name source code variable name
     * @param ref  the VariableReference produced by the statement
     */
    public void register(String name, VariableReference ref) {
        variables.put(name, ref);
    }

    /**
     * Register a variable with its fully-resolved generic type.
     *
     * @param name        source code variable name
     * @param ref         the VariableReference produced by the statement
     * @param genericType the fully-resolved GenericClass (with type parameters)
     */
    public void register(String name, VariableReference ref, GenericClass<?> genericType) {
        variables.put(name, ref);
        if (genericType != null) {
            genericTypes.put(name, genericType);
        }
    }

    /**
     * Look up a variable by its source code name.
     *
     * @param name the variable name
     * @return the VariableReference, or null if not defined
     */
    public VariableReference resolve(String name) {
        return variables.get(name);
    }

    /**
     * Look up the generic type of a variable by its source code name.
     *
     * @param name the variable name
     * @return the GenericClass, or null if not tracked
     */
    public GenericClass<?> resolveGenericType(String name) {
        return genericTypes.get(name);
    }

    /**
     * Check if a variable name is defined in this scope.
     */
    public boolean isDefined(String name) {
        return variables.containsKey(name);
    }

    /**
     * Find the GenericClass tracked for a given VariableReference by reverse-lookup.
     *
     * @param ref the VariableReference to find
     * @return the GenericClass, or null if not found
     */
    public GenericClass<?> findGenericTypeForRef(VariableReference ref) {
        for (Map.Entry<String, VariableReference> entry : variables.entrySet()) {
            if (entry.getValue() == ref) {
                return genericTypes.get(entry.getKey());
            }
        }
        return null;
    }
}
