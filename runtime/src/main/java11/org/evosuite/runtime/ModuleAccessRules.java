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

/**
 * Applies the rule of {@code AccessibleObject.checkCanSetAccessible} for a caller in the
 * unnamed module against the module graph as it actually is, minus the opens and exports
 * EvoSuite added for its own runtime. See {@link ReflectionAccess} for why that is needed
 * and how the private list reaches us; this class holds the part of it that cannot be
 * written without the Java 9 module API, and is loaded reflectively so that a Java 8 JVM
 * never links it.
 */
final class ModuleAccessRules implements ModuleAccessPolicy {

    /**
     * Whether this JVM permits illegal reflective access by default (JDK 9-15 without
     * {@code --illegal-access=deny}): probed on a package no EvoSuite launcher opens.
     */
    private final boolean legacyPermit;

    ModuleAccessRules() {
        this.legacyPermit = probeLegacyPermit();
    }

    private static boolean probeLegacyPermit() {
        if (ReflectionAccess.javaSpecificationVersion() > 15) {
            return false;
        }
        try {
            Field crc = java.util.zip.CRC32.class.getDeclaredField("crc");
            if (Object.class.getModule().isOpen("java.util.zip", ModuleAccessRules.class.getModule())) {
                return false; // opened explicitly, tells us nothing
            }
            return crc.trySetAccessible();
        } catch (NoSuchFieldException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean isAccessAllowed(AccessibleObject ao) {
        Class<?> declaringClass = declaringClassOf(ao);
        Module declaringModule = declaringClass.getModule();
        if (!declaringModule.isNamed()) {
            // SUT or classpath code, same module as the caller
            return true;
        }
        if (legacyPermit) {
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
        if (ReflectionAccess.isPrivateToEvoSuite(declaringModule.getName() + "/" + pn)) {
            return false;
        }
        Module caller = ModuleAccessRules.class.getModule();
        if (isClassPublic && isMemberPublic && declaringModule.isExported(pn, caller)) {
            return true;
        }
        return declaringModule.isOpen(pn, caller);
    }

    @Override
    public boolean trySetAccessible(AccessibleObject ao) {
        return ao.trySetAccessible();
    }

    @Override
    public RuntimeException denied(AccessibleObject ao) {
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
                .append("\" to ").append(ModuleAccessRules.class.getModule());

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
