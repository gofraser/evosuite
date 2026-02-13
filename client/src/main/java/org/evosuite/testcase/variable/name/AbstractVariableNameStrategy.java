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

package org.evosuite.testcase.variable.name;

import org.evosuite.testcase.variable.VariableReference;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The basic implementation for a {@link VariableNameStrategy}.
 *
 * @author Afonso Oliveira
 */
public abstract class AbstractVariableNameStrategy implements VariableNameStrategy {

    protected final Map<VariableReference, String> variableNames = new ConcurrentHashMap<>();

    @Override
    public String getNameForVariable(VariableReference variable) {
        return variableNames.computeIfAbsent(variable, this::createNameForVariable);
    }

    @Override
    public Optional<VariableReference> getVariableFromName(String variableName) {
        for (Map.Entry<VariableReference, String> entry : variableNames.entrySet()) {
            if (variableName.equals(entry.getValue())) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    @Override
    public Collection<String> getVariableNames() {
        return variableNames.values();
    }

    /**
     * Create a name to uniquely identify a variable.
     *
     * @param variable The variable to be named.
     * @return The name choosen for the variable.
     */
    public abstract String createNameForVariable(VariableReference variable);

}
