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
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Answers {@code setAccessible} the way the JVM the user configured would answer it.
 *
 * <p>The client JVM is started with a long list of {@code --add-opens} that EvoSuite's own
 * mocks need (see {@code ExecutionModeUtils}). The SUT shares the unnamed module with the
 * runtime, so without intervention it inherits every one of them during the search and
 * then sees {@link InaccessibleObjectException} when the generated test runs in a JVM the
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
 */
public class ReflectionAccess {

    /**
     * System property holding the comma-separated {@code module/package} pairs that EvoSuite
     * opened or exported for its own runtime, i.e. the ones the SUT must not see.
     */
    public static final String PRIVATE_ACCESS_PROPERTY = "evosuite.private_module_access";

    private static volatile Set<String> privateAccess = readPrivateAccess();

    /**
     * Whether this JVM permits illegal reflective access by default (JDK 9-15 without
     * {@code --illegal-access=deny}): probed on a package no EvoSuite launcher opens.
     */
    private static final boolean LEGACY_PERMIT = probeLegacyPermit();

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

    private static boolean probeLegacyPermit() {
        if (java.lang.Runtime.version().feature() > 15) {
            return false;
        }
        try {
            Field crc = java.util.zip.CRC32.class.getDeclaredField("crc");
            if (Object.class.getModule().isOpen("java.util.zip", ReflectionAccess.class.getModule())) {
                return false; // opened explicitly, tells us nothing
            }
            return crc.trySetAccessible();
        } catch (NoSuchFieldException | RuntimeException e) {
            return false;
        }
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
     * @throws InaccessibleObjectException if a stock JVM would deny the request
     */
    public static void setAccessible(AccessibleObject ao, boolean flag) {
        if (!flag || isAccessAllowed(ao)) {
            ao.setAccessible(flag);
            return;
        }
        throw denied(ao);
    }

    /**
     * Replacement for {@link AccessibleObject#trySetAccessible()}.
     *
     * @param ao the accessible object
     * @return whether the accessible flag was set
     */
    public static boolean trySetAccessible(AccessibleObject ao) {
        if (!isAccessAllowed(ao)) {
            return false;
        }
        return ao.trySetAccessible();
    }

    /**
     * Replacement for the static {@link AccessibleObject#setAccessible(AccessibleObject[], boolean)}.
     * Like the real JDK, this validates every element before flipping any of them: a
     * denial part-way through the array leaves every element's accessible flag
     * untouched, including the ones that would have been allowed.
     *
     * @param array the accessible objects
     * @param flag  the new value for the accessible flag
     * @throws InaccessibleObjectException if a stock JVM would deny the request for any element
     */
    public static void setAccessible(AccessibleObject[] array, boolean flag) {
        if (flag) {
            for (AccessibleObject ao : array) {
                if (!isAccessAllowed(ao)) {
                    throw denied(ao);
                }
            }
        }
        for (AccessibleObject ao : array) {
            ao.setAccessible(flag);
        }
    }

    private static boolean isAccessAllowed(AccessibleObject ao) {
        Class<?> declaringClass = declaringClassOf(ao);
        Module declaringModule = declaringClass.getModule();
        if (!declaringModule.isNamed()) {
            // SUT or classpath code, same module as the caller
            return true;
        }
        if (LEGACY_PERMIT) {
            return true;
        }

        String pn = declaringClass.getPackageName();
        boolean isClassPublic = Modifier.isPublic(declaringClass.getModifiers());
        boolean isMemberPublic = Modifier.isPublic(modifiersOf(ao));

        // The one-arg overloads only see unconditional exports/opens.
        if (isClassPublic && isMemberPublic && declaringModule.isExported(pn)) {
            return true;
        }
        if (declaringModule.isOpen(pn)) {
            return true;
        }
        // Qualified opens/exports to the unnamed module come from --add-opens/--add-exports:
        // they count unless EvoSuite added them for its own runtime.
        if (privateAccess.contains(declaringModule.getName() + "/" + pn)) {
            return false;
        }
        Module caller = ReflectionAccess.class.getModule();
        if (isClassPublic && isMemberPublic && declaringModule.isExported(pn, caller)) {
            return true;
        }
        return declaringModule.isOpen(pn, caller);
    }

    private static InaccessibleObjectException denied(AccessibleObject ao) {
        Class<?> declaringClass = declaringClassOf(ao);
        Module declaringModule = declaringClass.getModule();
        String pn = declaringClass.getPackageName();
        boolean exports = Modifier.isPublic(declaringClass.getModifiers()) && Modifier.isPublic(modifiersOf(ao));

        StringBuilder msg = new StringBuilder("Unable to make ");
        if (ao instanceof Field) {
            msg.append("field ");
        }
        msg.append(ao).append(" accessible: ").append(declaringModule).append(" does not \"")
                .append(exports ? "exports" : "opens").append(' ').append(pn)
                .append("\" to ").append(ReflectionAccess.class.getModule());

        InaccessibleObjectException e = new InaccessibleObjectException(msg.toString());
        // The generated test's verifyException keys on the top frame, which in a stock
        // JVM (and in a test JVM that did not instrument the SUT) is the JDK's own.
        e.setStackTrace(deniedStackTrace(ao));
        return e;
    }

    private static StackTraceElement[] deniedStackTrace(AccessibleObject ao) {
        String ownerClass = ao instanceof Field ? Field.class.getName()
                : ao instanceof Constructor ? Constructor.class.getName()
                : ao instanceof Method ? Method.class.getName()
                : AccessibleObject.class.getName();
        String ownerFile = ownerClass.substring(ownerClass.lastIndexOf('.') + 1) + ".java";
        return new StackTraceElement[] {
                new StackTraceElement(AccessibleObject.class.getName(), "checkCanSetAccessible",
                        "AccessibleObject.java", -1),
                new StackTraceElement(ownerClass, "setAccessible", ownerFile, -1),
        };
    }

    private static Class<?> declaringClassOf(AccessibleObject ao) {
        if (ao instanceof Field) {
            return ((Field) ao).getDeclaringClass();
        }
        if (ao instanceof Executable) {
            return ((Executable) ao).getDeclaringClass();
        }
        throw new IllegalArgumentException("Unsupported AccessibleObject: " + ao.getClass());
    }

    private static int modifiersOf(AccessibleObject ao) {
        if (ao instanceof Field) {
            return ((Field) ao).getModifiers();
        }
        if (ao instanceof Executable) {
            return ((Executable) ao).getModifiers();
        }
        throw new IllegalArgumentException("Unsupported AccessibleObject: " + ao.getClass());
    }
}
