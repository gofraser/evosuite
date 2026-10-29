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
 */package org.evosuite.setup;

import org.evosuite.Properties;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericConstructor;

/**
 * Decides which generators and calls cannot work in headless mode (no display), so the test
 * cluster can keep them out of the search. Stateless: it only reads {@link Properties} and the
 * reflective shape of the member.
 */
final class HeadlessGeneratorFilter {

    private HeadlessGeneratorFilter() {
    }

    static boolean isIncompatible(GenericAccessibleObject<?> generator) {
        return isIncompatible(generator, Properties.HEADLESS_FILTER_JDK_GUI_GENERATORS);
    }

    static boolean isIncompatible(GenericAccessibleObject<?> generator,
                                  boolean filterJdkGuiComponents) {
        if (!Properties.HEADLESS_MODE) {
            return false;
        }

        // Filter any generator declared on a JDK Swing/AWT Component subclass
        // (eg JPopupMenu.Separator, JSeparator, JPanel).  Even when their
        // constructors do not declare HeadlessException, constructing such a
        // component can transitively load the platform Look-and-Feel (Aqua on
        // macOS) or touch the real GraphicsEnvironment.  On macOS that calls
        // into AppKit, which aborts the JVM (SIGABRT / signal 6) when touched
        // off the main thread.  Mock classes (org.evosuite.runtime.mock.*)
        // live outside java./javax. so are not affected.
        Class<?> declaringClass = generator.getDeclaringClass();
        if (filterJdkGuiComponents && isJdkGuiComponent(declaringClass)
                && !(Properties.HEADLESS_ALLOW_JDK_GUI_WRAPPING_CONSTRUCTORS
                && isComponentWrappingConstructor(generator))) {
            return true;
        }

        // Filter constructors that declare HeadlessException — these call
        // GraphicsEnvironment.checkHeadless() and will always fail in headless
        // mode.  This covers Window/Frame/Dialog as well as heavyweight AWT
        // components like ScrollPane, Button, Label, etc.
        if (generator.isConstructor()) {
            GenericConstructor gc = (GenericConstructor) generator;
            boolean declaresHeadless = false;
            for (Class<?> ex : gc.getConstructor().getExceptionTypes()) {
                if (ex == java.awt.HeadlessException.class) {
                    declaresHeadless = true;
                    break;
                }
            }
            if (declaresHeadless) {
                String declaringName = declaringClass.getCanonicalName();
                // If this IS a JDK class that we mock (e.g. JFrame itself),
                // keep filtering — reflection bypasses the mock.
                if (declaringName != null && MockList.shouldBeMocked(declaringName)) {
                    return true;
                }
                // If the declaring class has a mocked ancestor (e.g. CUT extends
                // JFrame), the mock handles headless via bytecode instrumentation
                // — don't filter.
                if (hasMockedAncestor(declaringClass)) {
                    return false;
                }
                // Keep filtering JDK classes and Window hierarchy constructors,
                // which are known to invoke GraphicsEnvironment.checkHeadless().
                if (isJdkType(declaringClass)
                        || isAssignableToTypeName(declaringClass, "java.awt.Window")) {
                    return true;
                }
                // For non-JDK CUT classes, a declared HeadlessException alone is
                // too coarse as a filter and can hide testable code paths.
                return false;
            }
        }

        // Filter methods/fields that return abstract AWT types which have no concrete
        // implementation in a headless environment (e.g., GraphicsConfiguration,
        // GraphicsDevice).  Without this filter, EvoSuite picks generators like
        // Component.getGraphicsConfiguration() which return null in headless mode,
        // producing broken test chains instead of falling back to mocking.
        if (generator.isMethod() || generator.isField()) {
            Class<?> returnType = generator.getGeneratedClass().getRawClass();
            if (returnType != null
                    && java.lang.reflect.Modifier.isAbstract(returnType.getModifiers())
                    && isHeadlessOnlyType(returnType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns true for abstract AWT/Swing types that have no concrete implementation available in a
     * headless environment. These types can only be obtained via mocking when running headless.
     */
    private static boolean isHeadlessOnlyType(Class<?> type) {
        String name = type.getName();
        return name.equals("java.awt.GraphicsConfiguration")
                || name.equals("java.awt.GraphicsDevice")
                || name.equals("java.awt.GraphicsEnvironment");
    }

    /**
     * Returns true for a constructor that takes an existing {@link java.awt.Component}, such as
     * {@code JScrollPane(Component)}: it wraps an object (often the class under test, which then
     * gets a real parent) rather than standing in for a typed parameter with a default component.
     */
    private static boolean isComponentWrappingConstructor(GenericAccessibleObject<?> generator) {
        if (!generator.isConstructor()) {
            return false;
        }
        for (Class<?> parameterType : ((GenericConstructor) generator).getConstructor().getParameterTypes()) {
            if (java.awt.Component.class.isAssignableFrom(parameterType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the given class is a JDK Swing/AWT {@link java.awt.Component} subclass, ie
     * declared in {@code java.awt.*} or {@code javax.swing.*} (including nested classes like {@code
     * javax.swing.JPopupMenu$Separator}). EvoSuite's own mock classes live in {@code
     * org.evosuite.runtime.mock.*} and are not matched.
     */
    private static boolean isJdkGuiComponent(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        try {
            if (!java.awt.Component.class.isAssignableFrom(clazz)) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        return ClassOrigin.isJdkGuiName(clazz.getName());
    }

    /**
     * Returns {@code true} if any class in {@code clazz}'s superclass chain has a registered
     * OverrideMock. This means bytecode instrumentation will replace the superclass reference,
     * allowing the mock to handle headless construction.
     */
    private static boolean hasMockedAncestor(Class<?> clazz) {
        for (Class<?> c = clazz.getSuperclass(); c != null; c = c.getSuperclass()) {
            String name = c.getCanonicalName();
            if (name != null && MockList.shouldBeMocked(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAssignableToTypeName(Class<?> type, String superTypeName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (superTypeName.equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJdkType(Class<?> type) {
        return ClassOrigin.isJavaApiName(type.getName());
    }
}
