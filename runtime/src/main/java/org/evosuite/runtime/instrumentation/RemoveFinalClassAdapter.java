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

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Remove final modifier from classes.
 */
public class RemoveFinalClassAdapter extends ClassVisitor {

    public static final Set<String> finalClasses = new LinkedHashSet<>();

    /**
     * Methods whose ACC_FINAL was stripped, keyed by
     * "declaring class binary name#method name#method descriptor". Needed because,
     * once stripped, {@code Method.getModifiers()} on the instrumented class no
     * longer reports the method as final, so callers that need the original
     * bytecode modifier (eg to decide whether Mockito can safely stub it, given
     * that the same class may be loaded unstripped in the generated test's JVM)
     * must consult this set instead.
     */
    private static final Set<String> finalMethods = new LinkedHashSet<>();

    private String currentClassName;

    /**
     * <p>Constructor for RemoveFinalClassAdapter.</p>
     *
     * @param cv a {@link org.objectweb.asm.ClassVisitor} object.
     */
    public RemoveFinalClassAdapter(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    /**
     * Remove "final" accessor from class definition.
     */
    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        currentClassName = name.replace('/', '.');
        if ((access & Opcodes.ACC_FINAL) == Opcodes.ACC_FINAL) {
            finalClasses.add(currentClassName);
        }
        if ((access & Opcodes.ACC_ABSTRACT) == Opcodes.ACC_ABSTRACT
                && (access & Opcodes.ACC_PUBLIC) == 0
                && (access & Opcodes.ACC_PRIVATE) == 0
                && (access & Opcodes.ACC_PROTECTED) == 0) {
            // If a class is abstract and default-accessible, then we make it public to allow
            // Mockito to create mocks
            super.visit(version, (access & ~Opcodes.ACC_FINAL) | Opcodes.ACC_PUBLIC, name, signature, superName,
                    interfaces);
        } else {
            super.visit(version, access & ~Opcodes.ACC_FINAL, name, signature, superName, interfaces);
        }
    }

    /**
     * Remove "final" accessor from inner class definition.
     */
    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        if ((access & Opcodes.ACC_FINAL) == Opcodes.ACC_FINAL) {
            finalClasses.add(name.replace('/', '.'));
        }
        super.visitInnerClass(name, outerName, innerName, access & ~Opcodes.ACC_FINAL);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        if ((access & Opcodes.ACC_FINAL) == Opcodes.ACC_FINAL) {
            finalMethods.add(methodKey(currentClassName, name, desc));
        }
        return super.visitMethod(access & ~Opcodes.ACC_FINAL, name, desc, signature, exceptions);
    }

    private static String methodKey(String className, String name, String desc) {
        return className + "#" + name + "#" + desc;
    }

    /**
     * Records that the given method (declared by {@code className}, in binary
     * dotted form) was final in the original bytecode before instrumentation
     * stripped it. Used by adapters other than this one (eg {@code NonTargetClassAdapter})
     * that also remove ACC_FINAL from methods but do not otherwise share this class's
     * per-class bookkeeping.
     *
     * @param className declaring class, dotted binary name (nested classes use '$')
     * @param name      method name
     * @param desc      method descriptor
     */
    public static void recordFinalMethod(String className, String name, String desc) {
        finalMethods.add(methodKey(className, name, desc));
    }

    /**
     * Checks whether the given method was final in the original, uninstrumented
     * bytecode.
     *
     * @param className declaring class, dotted binary name (nested classes use '$'),
     *                  ie {@link Class#getName()}
     * @param name      method name
     * @param desc      method descriptor, eg as produced by {@link Type#getMethodDescriptor(Method)}
     * @return true if the method was final before instrumentation removed it
     */
    public static boolean isFinalMethod(String className, String name, String desc) {
        return finalMethods.contains(methodKey(className, name, desc));
    }

    /**
     * Convenience overload of {@link #isFinalMethod(String, String, String)} taking
     * a reflection {@link Method} directly.
     *
     * @param method the method to check
     * @return true if the method was final before instrumentation removed it
     */
    public static boolean isFinalMethod(Method method) {
        return isFinalMethod(method.getDeclaringClass().getName(), method.getName(),
                Type.getMethodDescriptor(method));
    }

    /**
     * <p>reset.</p>
     */
    public static void reset() {
        finalClasses.clear();
        finalMethods.clear();
    }
}
