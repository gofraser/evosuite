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
