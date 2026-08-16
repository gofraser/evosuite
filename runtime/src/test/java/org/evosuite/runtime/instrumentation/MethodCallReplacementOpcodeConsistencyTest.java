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
package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.RuntimeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * The replacement table records, for every replaced call, the opcode the original call is
 * expected to use. Getting that wrong used to break instrumentation of whole classes, because
 * the fallback branch was re-emitted with the opcode of the (static) replacement rather than the
 * one of the original instance method. Re-emission no longer trusts the stored opcode, but the
 * table is large and keeps growing, so it is still checked against reality here.
 */
public class MethodCallReplacementOpcodeConsistencyTest {

    @BeforeEach
    public void enableJvmMocking() {
        RuntimeSettings.mockJVMNonDeterminism = true;
        MethodCallReplacementCache.resetSingleton();
    }

    @AfterEach
    public void resetState() {
        RuntimeSettings.mockJVMNonDeterminism = false;
        MethodCallReplacementCache.resetSingleton();
    }

    @Test
    public void testDeclaredOriginalOpcodesMatchTheRealMethods() {
        List<String> mismatches = new ArrayList<>();
        int checked = 0;

        for (MethodCallReplacement replacement : MethodCallReplacementCache.getInstance()
                .getAllReplacementCalls()) {

            if ("<init>".equals(replacement.getMethodName())) {
                // Constructor replacements never re-emit the original call: they are handled by
                // the constructor-to-static-factory path, which builds an INVOKESTATIC instead.
                continue;
            }

            Class<?> owner = resolve(replacement.getClassName());
            if (owner == null) {
                // Not on the test classpath (eg JDK-version specific or nested-class names that
                // were registered with their canonical rather than their binary name).
                continue;
            }

            Method method = findMethod(owner, replacement.getMethodName(), replacement.getDesc());
            if (method == null) {
                continue;
            }

            int expected;
            if (Modifier.isStatic(method.getModifiers())) {
                expected = Opcodes.INVOKESTATIC;
            } else if (owner.isInterface()) {
                expected = Opcodes.INVOKEINTERFACE;
            } else {
                expected = Opcodes.INVOKEVIRTUAL;
            }

            checked++;
            if (replacement.getOrigOpcode() != expected) {
                mismatches.add(replacement.getClassName() + "." + replacement.getMethodName()
                        + replacement.getDesc() + ": declared " + name(replacement.getOrigOpcode())
                        + " but the method is " + name(expected));
            }
        }

        Assertions.assertTrue(checked > 100,
                "Only " + checked + " replacements could be checked, the test is not doing its job");
        Assertions.assertTrue(mismatches.isEmpty(),
                "Replacement table declares wrong original opcodes:\n" + String.join("\n", mismatches));
    }

    private static Class<?> resolve(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false,
                    MethodCallReplacementOpcodeConsistencyTest.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String name, String desc) {
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) {
                return m;
            }
        }
        return null;
    }

    private static String name(int opcode) {
        switch (opcode) {
            case Opcodes.INVOKESTATIC:
                return "INVOKESTATIC";
            case Opcodes.INVOKEVIRTUAL:
                return "INVOKEVIRTUAL";
            case Opcodes.INVOKEINTERFACE:
                return "INVOKEINTERFACE";
            case Opcodes.INVOKESPECIAL:
                return "INVOKESPECIAL";
            default:
                return "opcode " + opcode;
        }
    }
}
