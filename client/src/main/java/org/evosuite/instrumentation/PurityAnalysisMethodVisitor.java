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
package org.evosuite.instrumentation;

import org.evosuite.assertion.CheapPurityAnalyzer;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * It collects bytecode instructions for further purity analysis in the
 * <code>CheapPurityAnalyzer</code> class:
 * <ul>
 *     <li>PUTSTATIC</li>
 *     <li>PUTFIELD</li>
 *     <li>INVOKESTATIC</li>
 *     <li>INVOKESPECIAL</li>
 *     <li>INVOKEINTERFACE</li>
 *     <li>INVOKEVIRTUAL</li>
 *     <li>array-store opcodes (conservatively treated as field updates)</li>
 * </ul>
 * This class only reads the existing bytecode.
 *
 * @author Juan Galeotti
 */
public class PurityAnalysisMethodVisitor extends MethodVisitor {

    private boolean updatesField;
    private boolean hasArrayStore;
    private boolean hasFieldLoad;
    private final CheapPurityAnalyzer purityAnalyzer;
    private final String classNameWithDots;
    private final String methodName;
    private final String descriptor;

    /**
     * <p>Constructor for PutStaticMethodAdapter.</p>
     *
     * @param className      a {@link java.lang.String} object.
     * @param methodName     a {@link java.lang.String} object.
     * @param descriptor     a {@link java.lang.String} object.
     * @param mv             a {@link org.objectweb.asm.MethodVisitor} object.
     * @param purityAnalyzer a {@link CheapPurityAnalyzer} object.
     */
    public PurityAnalysisMethodVisitor(String className, String methodName,
                                       String descriptor, MethodVisitor mv,
                                       CheapPurityAnalyzer purityAnalyzer) {
        super(Opcodes.ASM9, mv);
        this.updatesField = false;
        this.purityAnalyzer = purityAnalyzer;
        this.classNameWithDots = className.replace('/', '.');
        this.methodName = methodName;
        this.descriptor = descriptor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitFieldInsn(int opcode, String owner, String name,
                               String desc) {
        if (opcode == Opcodes.PUTSTATIC || opcode == Opcodes.PUTFIELD) {
            updatesField = true;
        } else if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
            hasFieldLoad = true;
        }
        super.visitFieldInsn(opcode, owner, name, desc);
    }

    @Override
    public void visitInsn(int opcode) {
        // A precise flow-based check for impure array stores would require
        // buffering the entire method, which is too costly on large classes.
        // As a cheap streaming approximation, we flag array stores as impure
        // only when the method also reads from a field (GETFIELD/GETSTATIC),
        // which is a strong indicator that the stored-into array may be the
        // field itself rather than a fresh local.
        if (isArrayStoreOpcode(opcode)) {
            hasArrayStore = true;
        }
        super.visitInsn(opcode);
    }

    public boolean updatesField() {
        return updatesField;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name,
                                String desc, boolean itf) {

        String targetClassName = owner.replace('/', '.');
        if (targetClassName.equals(org.evosuite.runtime.Random.class.getCanonicalName())
                || !BytecodeInstrumentation.checkIfEvoSuitePackage(targetClassName)) {
            //Only ignore EvoSuite callbacks
            if (opcode == Opcodes.INVOKESTATIC) {
                this.purityAnalyzer.addStaticCall(classNameWithDots,
                        methodName, descriptor, targetClassName, name, desc);
            } else if (opcode == Opcodes.INVOKEVIRTUAL) {
                this.purityAnalyzer.addVirtualCall(classNameWithDots,
                        methodName, descriptor, targetClassName, name, desc);

            } else if (opcode == Opcodes.INVOKEINTERFACE) {
                this.purityAnalyzer.addInterfaceCall(classNameWithDots,
                        methodName, descriptor, targetClassName, name, desc);

            } else if (opcode == Opcodes.INVOKESPECIAL) {
                this.purityAnalyzer.addSpecialCall(classNameWithDots,
                        methodName, descriptor, targetClassName, name, desc);
            }
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    @Override
    public void visitEnd() {
        super.visitEnd();
        if (!updatesField && hasArrayStore && hasFieldLoad) {
            updatesField = true;
        }
        if (updatesField) {
            purityAnalyzer.addUpdatesFieldMethod(classNameWithDots, methodName, descriptor);
        }
    }

    private static boolean isArrayStoreOpcode(int opcode) {
        return opcode == Opcodes.IASTORE
                || opcode == Opcodes.LASTORE
                || opcode == Opcodes.FASTORE
                || opcode == Opcodes.DASTORE
                || opcode == Opcodes.AASTORE
                || opcode == Opcodes.BASTORE
                || opcode == Opcodes.CASTORE
                || opcode == Opcodes.SASTORE;
    }
}
