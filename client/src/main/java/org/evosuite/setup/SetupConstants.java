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
package org.evosuite.setup;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Constants used in the setup package.
 */
public class SetupConstants {

    public static final Set<String> PRIMITIVE_TYPES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "int", "short", "float", "double", "byte", "char", "boolean", "long"
    )));

    public static final Set<String> BLACKLIST_EVOSUITE_PRIMITIVES;

    static {
        Set<String> blacklist = new LinkedHashSet<>(PRIMITIVE_TYPES);
        blacklist.add(java.lang.Enum.class.getName());
        blacklist.add(java.lang.String.class.getName());
        blacklist.add(java.lang.Class.class.getName());
        blacklist.add(java.lang.ThreadGroup.class.getName()); // may lead to EvoSuite killing all threads
        BLACKLIST_EVOSUITE_PRIMITIVES = Collections.unmodifiableSet(blacklist);
    }

    public static final Set<String> FORBIDDEN_PACKAGES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "java.",
            "sun.",
            "org.exsyst",
            "de.unisb.cs.st.evosuite",
            "de.unisb.cs.st.specmate",
            "javax.",
            "org.xml",
            "org.w3c",
            "apple.",
            "com.apple.",
            "org.omg.",
            "sunw.",
            "org.jcp.",
            "org.ietf.",
            "daikon.",
            "jdk."
    )));

    private SetupConstants() {
        // prevent instantiation
    }
}
