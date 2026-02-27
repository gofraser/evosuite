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
package org.evosuite.testcase.utils;

import java.util.ArrayList;

public class HeuristicsUtil {
    /**
     * List of particles of a method name that can be excluded or avoided when suggesting names.
     */
    private static ArrayList<String> avoidableParticles = new ArrayList<String>() {
        {
            add("get");
            add("to");
            add("has");
            add("is");
            add("are");
        }
    };

    /**
     * Indicates whether the first word of a method can be avoided/excluded
     * on method name suggestion.
     *
     * @param firstWord the word to check
     * @return true if the word is avoidable
     */
    public static boolean containsAvoidableParticle(String firstWord) {
        return avoidableParticles.contains(firstWord);
    }

    /**
     * Splits camelCase strings into a list of words.
     *
     * @param name the string to split
     * @return the list of words
     */
    public static ArrayList<String> separateByCamelCase(String name) {
        ArrayList<String> separatedName = new ArrayList<>();
        for (String word : name.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])")) {
            separatedName.add(word);
        }
        return separatedName;
    }

}
