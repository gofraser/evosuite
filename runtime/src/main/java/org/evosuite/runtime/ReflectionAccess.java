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
package org.evosuite.runtime;

import java.lang.reflect.AccessibleObject;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Answers {@code setAccessible} the way the JVM the user configured would answer it.
 *
 * <p>The client JVM is started with a long list of {@code --add-opens} that EvoSuite's own
 * mocks need (see {@code ExecutionModeUtils}). The SUT shares the unnamed module with the
 * runtime, so without intervention it inherits every one of them during the search and
 * then sees {@code InaccessibleObjectException} when the generated test runs in a JVM the
 * user started without them. Instrumented code therefore has its
 * {@code setAccessible}/{@code trySetAccessible} calls redirected here, which applies the
 * rule of {@code AccessibleObject.checkCanSetAccessible} for a caller in the unnamed module
 * (the caller is always SUT or classpath code) against the module graph as it actually is,
 * <em>minus</em> the opens and exports EvoSuite added for itself. The master passes that
 * private list in the {@value #PRIVATE_ACCESS_PROPERTY} system property, after removing
 * whatever the user granted on EvoSuite's own command line or in {@code JDK_JAVA_OPTIONS};
 * a package the user opened for the SUT (liferay's XStream needs {@code java.base/java.util}
 * on JDK 17 in production too) is honoured, a package only EvoSuite opened is not. In a
 * test JVM the property is absent and the rule reduces to the JVM's real answer.
 *
 * <p>On JDK 9 to 15 the default {@code --illegal-access=permit} lets code in the unnamed
 * module reflect into the JDK 8 packages anyway, with a warning; that is detected by
 * probing a package EvoSuite never opens, and then every request is allowed as the JVM
 * would. Protected static members of a superclass are treated as denied.
 *
 * <p>All of that needs the module API, which arrived in Java 9, whereas this module is
 * compiled at target 1.8 and is shipped to users who may run their generated tests on a
 * Java 8 JVM. Everything that names a Java 9 type therefore lives in
 * {@code ModuleAccessRules} under {@code src/main/java11}, reached only through
 * {@link ModuleAccessPolicy} and only loaded once {@link #javaSpecificationVersion()} says
 * there is a module system to consult. Where there is none, there is also nothing to
 * emulate: a Java 8 JVM grants every {@code setAccessible}, and so do we.
 */
public class ReflectionAccess {

    /**
     * System property holding the comma-separated {@code module/package} pairs that EvoSuite
     * opened or exported for its own runtime, i.e. the ones the SUT must not see.
     */
    public static final String PRIVATE_ACCESS_PROPERTY = "evosuite.private_module_access";

    private static volatile Set<String> privateAccess = readPrivateAccess();

    /** The feature version of this JVM, or -1 if it could not be determined. */
    private static final int SPEC_VERSION = readSpecVersion();

    /** The module rules, or null on a JVM that has no module system to emulate. */
    private static final ModuleAccessPolicy POLICY = loadPolicy();

    private ReflectionAccess() {
    }

    private static Set<String> readPrivateAccess() {
        String value = java.lang.System.getProperty(PRIVATE_ACCESS_PROPERTY);
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        for (String entry : value.split(",")) {
            if (!entry.trim().isEmpty()) {
                set.add(entry.trim());
            }
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * Reads the feature version from {@code java.specification.version}, which is "1.8" on
     * Java 8 and the bare number from Java 9 on. {@code Runtime.version()} would be the
     * obvious source but does not exist on Java 8, and its {@code feature()} accessor only
     * arrived in Java 10.
     */
    private static int readSpecVersion() {
        String value = java.lang.System.getProperty("java.specification.version");
        if (value == null) {
            return -1;
        }
        value = value.trim();
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ModuleAccessPolicy loadPolicy() {
        if (SPEC_VERSION >= 0 && SPEC_VERSION < 9) {
            return null;
        }
        try {
            Class<?> rules = Class.forName("org.evosuite.runtime.ModuleAccessRules", true,
                    ReflectionAccess.class.getClassLoader());
            return (ModuleAccessPolicy) rules.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            // No module system after all, or a runtime jar built by a JDK 8 that never
            // compiled the rules: nothing to emulate, so grant everything as Java 8 does.
            // Say so on a JVM that should have had them, or the emulation switches itself
            // off without a trace and every setAccessible silently starts succeeding.
            if (SPEC_VERSION >= 9) {
                java.lang.System.err.println("EvoSuite: no module access rules on Java "
                        + SPEC_VERSION + " (" + t + "); setAccessible will not be restricted");
            }
            return null;
        }
    }

    /** @return the feature version of this JVM, or -1 if it could not be determined */
    static int javaSpecificationVersion() {
        return SPEC_VERSION;
    }

    /**
     * @param modulePackage a {@code module/package} pair
     * @return whether EvoSuite opened it for its own runtime and must hide it from the SUT
     */
    static boolean isPrivateToEvoSuite(String modulePackage) {
        return privateAccess.contains(modulePackage);
    }

    /**
     * Re-reads {@value #PRIVATE_ACCESS_PROPERTY}. The property is read once when this class
     * is initialised, which is what a client JVM needs; this is for tests that set it later.
     */
    public static void reloadPrivateAccess() {
        privateAccess = readPrivateAccess();
    }

    /**
     * Replacement for {@link AccessibleObject#setAccessible(boolean)} (and its
     * {@code Field}/{@code Method}/{@code Constructor} overrides, which javac emits as
     * the call-site owner whenever the receiver is statically typed as one of them).
     *
     * @param ao   the accessible object
     * @param flag the new value for the accessible flag
     * @throws RuntimeException {@code InaccessibleObjectException} if a stock JVM would deny the request
     */
    public static void setAccessible(AccessibleObject ao, boolean flag) {
        if (!flag || isAccessAllowed(ao)) {
            ao.setAccessible(flag);
            return;
        }
        throw POLICY.denied(ao);
    }

    /**
     * Replacement for {@code AccessibleObject.trySetAccessible()}.
     *
     * @param ao the accessible object
     * @return whether the accessible flag was set
     */
    public static boolean trySetAccessible(AccessibleObject ao) {
        if (POLICY == null) {
            // Java 8: no module can refuse, and the method itself does not exist to call.
            ao.setAccessible(true);
            return true;
        }
        if (!POLICY.isAccessAllowed(ao)) {
            return false;
        }
        return POLICY.trySetAccessible(ao);
    }

    /**
     * Replacement for the static {@link AccessibleObject#setAccessible(AccessibleObject[], boolean)}.
     * Like the real JDK, this validates every element before flipping any of them: a
     * denial part-way through the array leaves every element's accessible flag
     * untouched, including the ones that would have been allowed.
     *
     * @param array the accessible objects
     * @param flag  the new value for the accessible flag
     * @throws RuntimeException {@code InaccessibleObjectException} if a stock JVM would deny any element
     */
    public static void setAccessible(AccessibleObject[] array, boolean flag) {
        if (flag) {
            for (AccessibleObject ao : array) {
                if (!isAccessAllowed(ao)) {
                    throw POLICY.denied(ao);
                }
            }
        }
        for (AccessibleObject ao : array) {
            ao.setAccessible(flag);
        }
    }

    private static boolean isAccessAllowed(AccessibleObject ao) {
        return POLICY == null || POLICY.isAccessAllowed(ao);
    }
}
