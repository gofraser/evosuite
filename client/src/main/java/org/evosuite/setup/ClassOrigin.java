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
package org.evosuite.setup;

/**
 * Whether a class comes from the JDK rather than from the code under test or its libraries.
 *
 * <p>The checks on a loaded class decide by module provenance: a module's name, and the loader
 * that defines it, are chosen by whoever built it, so a {@code java.}/{@code jdk.}-named module
 * counts only if {@link JavaModuleLookup#isFromSystemImage} says the running JDK's image resolved
 * it. The checks on a name are approximate: libraries also use {@code com.sun.} (rome, JavaMail),
 * and a user class can sit in a {@code javax.} package. Use them only where no class is loaded,
 * or where an approximation is the documented intent.
 */
public final class ClassOrigin {

    private ClassOrigin() {
    }

    /**
     * Whether {@code c} is a class of the running JDK, by module provenance. Since Java 9 JDK
     * modules are defined by the bootstrap loader ({@code java.base}) or the platform loader
     * ({@code java.sql}), so loader identity alone does not decide. Without a module system
     * (Java 8) only a bootstrap class counts. A class from the classpath is never a JDK class.
     *
     * @param c the class to check
     * @return true if {@code c} belongs to a module of the running JDK's image
     */
    public static boolean isJdk(Class<?> c) {
        JavaModuleLookup.Result result = JavaModuleLookup.lookupModule(c);
        if (result.isUnavailable()) {
            return c.getClassLoader() == null;
        }
        return JavaModuleLookup.isSystemImageJdkModule(result);
    }

    /**
     * Like {@link #isJdk(Class)}, but for deciding whether a class under an ambiguous JDK prefix
     * ({@code com.sun.}) is a library class: when the module system cannot answer the class is
     * assumed to be a JDK class, and JavaFX modules (and the bare module names {@code java} and
     * {@code jdk}) count as JDK although JavaFX is not part of the JDK's image.
     *
     * @param c the class to check
     * @return true if {@code c} is to be treated as JDK code
     */
    public static boolean isJdkOrJavaFx(Class<?> c) {
        if (c.getClassLoader() == null) {
            return true;
        }
        JavaModuleLookup.Result result = JavaModuleLookup.lookupModule(c);
        if (result.isUnavailable()) {
            return true;
        }
        if (!result.isNamed()) {
            return false;
        }
        String name = result.moduleName();
        return JavaModuleLookup.isSystemImageJdkModule(result)
                || name.startsWith("javafx.") || name.equals("java") || name.equals("jdk");
    }

    /**
     * Name-only approximation of {@link #isJdk(Class)}: the {@code java.}, {@code javax.},
     * {@code jdk.}, {@code sun.} and {@code com.sun.} packages.
     *
     * @param className the fully qualified class name, may be null
     * @return true if the name is in one of these packages
     */
    public static boolean isJdkName(String className) {
        return className != null && (className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun."));
    }

    /**
     * Name-only check for the standard API packages {@code java.} and {@code javax.}.
     *
     * @param className the fully qualified class name
     * @return true if the name is in one of these packages
     */
    public static boolean isJavaApiName(String className) {
        return className.startsWith("java.") || className.startsWith("javax.");
    }

    /**
     * Name-only check for the AWT and Swing packages {@code java.awt.} and {@code javax.swing.}.
     *
     * @param className the fully qualified class name
     * @return true if the name is in one of these packages
     */
    public static boolean isJdkGuiName(String className) {
        return className.startsWith("java.awt.") || className.startsWith("javax.swing.");
    }
}
