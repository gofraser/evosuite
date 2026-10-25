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
package org.evosuite.llm.response;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The failure families an error text belongs to, found by marker substrings. The hint blocks and
 * the repair-skip decisions all read the families from one classification of the message instead
 * of each keeping its own marker list.
 */
final class RepairFailureKinds {

    private static final List<String> DEPENDENCY_MISSING_MARKERS = Arrays.asList(
            "NoClassDefFoundError",
            "ClassNotFoundException",
            "Could not initialize class"
    );
    /**
     * A javac "cannot find symbol" error names a missing class only when its symbol line says so;
     * an invented method or variable is an ordinary source error that the model can fix.
     */
    private static final Pattern MISSING_CLASS_SYMBOL_PATTERN = Pattern.compile(
            "cannot find symbol[^\\n]*\\n(?:[^\\n]*\\n){0,3}?\\s*symbol:\\s+class\\b");
    private static final List<String> INITIALIZATION_FAILURE_MARKERS = Arrays.asList(
            "ExceptionInInitializerError",
            "NoClassDefFoundError",
            "Could not initialize class",
            "ClassNotFoundException",
            "UnsatisfiedLinkError",
            "LinkageError",
            "java.awt.AWTError",
            "HeadlessException",
            "Local GraphicsEnvironment must not be null"
    );
    /** Lower-case signals of a missing display toolkit. */
    private static final List<String> HEADLESS_GUI_SIGNALS = Arrays.asList(
            "awterror",
            "headlessexception",
            "graphicsenvironment"
    );
    private static final List<String> INSTANTIATION_FAILURE_MARKERS = Arrays.asList(
            "InstantiationException",
            "is abstract; cannot be instantiated",
            "cannot instantiate"
    );
    private static final List<String> MOCKITO_MISUSE_MARKERS = Arrays.asList(
            "NotAMockException",
            "UnfinishedStubbingException",
            "MissingMethodInvocationException",
            "org.mockito.exceptions.misusing"
    );
    private static final List<String> INDEXED_FIXTURE_FAILURE_MARKERS = Arrays.asList(
            "ArrayIndexOutOfBoundsException",
            "IndexOutOfBoundsException",
            "NegativeArraySizeException"
    );

    private static final RepairFailureKinds NONE = new RepairFailureKinds(null);

    private final boolean dependencyMissing;
    private final boolean initializationFailure;
    private final boolean headlessGui;
    private final boolean instantiationFailure;
    private final boolean mockingMisuse;
    private final boolean indexedFixtureShape;

    private RepairFailureKinds(String error) {
        if (error == null || error.isEmpty()) {
            dependencyMissing = false;
            initializationFailure = false;
            headlessGui = false;
            instantiationFailure = false;
            mockingMisuse = false;
            indexedFixtureShape = false;
            return;
        }
        dependencyMissing = containsAny(error, DEPENDENCY_MISSING_MARKERS)
                || MISSING_CLASS_SYMBOL_PATTERN.matcher(error).find();
        initializationFailure = containsAny(error, INITIALIZATION_FAILURE_MARKERS);
        headlessGui = containsAny(error.toLowerCase(), HEADLESS_GUI_SIGNALS);
        instantiationFailure = containsAny(error, INSTANTIATION_FAILURE_MARKERS);
        mockingMisuse = containsAny(error, MOCKITO_MISUSE_MARKERS);
        indexedFixtureShape = containsAny(error, INDEXED_FIXTURE_FAILURE_MARKERS);
    }

    static RepairFailureKinds of(String error) {
        return error == null || error.isEmpty() ? NONE : new RepairFailureKinds(error);
    }

    /** A class is missing: a run-time linkage error, or a javac error naming a class symbol. */
    boolean isDependencyMissing() {
        return dependencyMissing;
    }

    boolean isInitializationFailure() {
        return initializationFailure;
    }

    /** A GUI toolkit is not available in this JVM. */
    boolean isHeadlessGui() {
        return headlessGui;
    }

    boolean isInstantiationFailure() {
        return instantiationFailure;
    }

    boolean isMockingMisuse() {
        return mockingMisuse;
    }

    boolean isIndexedFixtureShape() {
        return indexedFixtureShape;
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
