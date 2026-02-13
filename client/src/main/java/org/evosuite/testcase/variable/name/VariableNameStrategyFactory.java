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

import org.evosuite.Properties;

/**
 * This class encapsulates the logic of creating a new naming strategy.
 *
 * <p>With the use of method get we can create a new variable naming strategy
 * using the default configuration or by providing the desired naming strategy.
 *
 * <pre>
 *     VariableNameStrategy namingStrategy = VariableNameStrategyFactory.get();
 * </pre>
 * or
 * <pre>
 *     VariableNameStrategy namingStrategy = VariableNameStrategyFactory.get(Properties.VARIABLE_NAMING_STRATEGY);
 * </pre>
 *
 * @author Afonso Oliveira
 * @see TypeBasedVariableNameStrategy
 */
public class VariableNameStrategyFactory {

    private VariableNameStrategyFactory() {
        // Nothing to do here
    }

    /**
     * Get the variable naming strategy.
     *
     * @param identifierNamingStrategy a {@link org.evosuite.Properties.VariableNamingStrategy} object.
     * @return a {@link org.evosuite.testcase.variable.name.VariableNameStrategy} object.
     */
    public static VariableNameStrategy get(Properties.VariableNamingStrategy identifierNamingStrategy) {
        if (Properties.VariableNamingStrategy.TYPE_BASED.equals(identifierNamingStrategy)) {
            return new TypeBasedVariableNameStrategy();
        }
        if (Properties.VariableNamingStrategy.HEURISTICS_BASED.equals(identifierNamingStrategy)) {
            return new HeuristicsVariableNameStrategy();
        } else {
            throw new IllegalArgumentException(String.format("Unknown variable naming strategy: %s",
                    identifierNamingStrategy));
        }
    }

    /**
     * Get the currently selected variable naming strategy.
     *
     * <p>The select strategy is defined in {@link Properties#VARIABLE_NAMING_STRATEGY}.
     *
     * @return The selected strategy.
     */
    public static VariableNameStrategy get() {
        return get(Properties.VARIABLE_NAMING_STRATEGY);
    }

    /**
     * gatherInformation.
     *
     * @return a boolean.
     */
    public static boolean gatherInformation() {
        return Properties.VariableNamingStrategy.HEURISTICS_BASED.equals(Properties.VARIABLE_NAMING_STRATEGY);
    }

}
