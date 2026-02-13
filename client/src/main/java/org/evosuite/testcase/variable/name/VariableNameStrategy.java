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

/**
 * The variable name strategy defines the logic behind how a name is generated.
 *
 * @author Afonso Oliveira
 * @see TypeBasedVariableNameStrategy
 */
public interface VariableNameStrategy {

    /**
     * Get the name that is used to identify a {@link VariableReference}.
     *
     * @param variable The variable for which the name should be generated.
     * @return The name to be used when referencing the variable.
     */
    String getNameForVariable(VariableReference variable);

    /**
     * Get the {@link VariableReference} from the variable name.
     *
     * @param variableName The name used to identify the variable.
     * @return The variable reference.
     */
    Optional<VariableReference> getVariableFromName(String variableName);

    /**
     * Get the collection of names used for the variables.
     *
     * @return The collection of variable names.
     */
    Collection<String> getVariableNames();

    /**
     * Allows to add information on dictionaries for variable naming.
     *
     * @param information a {@link java.util.Map} object.
     */
    void addVariableInformation(Map<String, Map<VariableReference, String>> information);


}
