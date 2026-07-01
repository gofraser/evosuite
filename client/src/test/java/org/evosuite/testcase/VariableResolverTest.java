/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3.0 of the License, or (at your option)
 * any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class VariableResolverTest {

    @Test
    public void testIsFunctionalInterfaceHandlesMissingMethodSignatureDependency() throws Exception {
        Class<?> rawClass = new MissingDependencyClassLoader().define(
                "org.evosuite.testcase.GeneratedFunctionalInterfaceWithMissingDependency",
                createInterfaceBytes("org/evosuite/testcase/GeneratedFunctionalInterfaceWithMissingDependency",
                        "missing/dependency/MissingType"));

        boolean result = assertDoesNotThrow(() -> invokeIsFunctionalInterface(rawClass));

        assertFalse(result, "Missing signature dependencies should be treated as non-functional interfaces");
    }

    private static boolean invokeIsFunctionalInterface(Class<?> rawClass) throws Exception {
        Method method = VariableResolver.class.getDeclaredMethod("isFunctionalInterface", Class.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, rawClass);
    }

    private static byte[] createInterfaceBytes(String internalName, String missingTypeInternalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);

        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "transform",
                "(L" + missingTypeInternalName + ";)L" + missingTypeInternalName + ";", null, null);
        mv.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class MissingDependencyClassLoader extends ClassLoader {
        MissingDependencyClassLoader() {
            super(VariableResolverTest.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
