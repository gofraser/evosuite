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
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PurityAnalysisMethodVisitorTest {

    @Test
    public void testArrayStorePurityClassification() {
        byte[] bytes = createArrayStorePurityClass();

        Map<String, Boolean> methodMutatesSharedState = new HashMap<>();
        CheapPurityAnalyzer analyzer = CheapPurityAnalyzer.getInstance();

        ClassReader reader = new ClassReader(bytes);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                PurityAnalysisMethodVisitor purityVisitor = new PurityAnalysisMethodVisitor(
                        "sample/ArrayPurity",
                        access,
                        name,
                        descriptor,
                        null,
                        analyzer);
                return new MethodVisitor(Opcodes.ASM9, purityVisitor) {
                    @Override
                    public void visitEnd() {
                        super.visitEnd();
                        methodMutatesSharedState.put(name + descriptor, purityVisitor.updatesField());
                    }
                };
            }
        };

        reader.accept(cv, ClassReader.EXPAND_FRAMES);

        // Pure: the array is allocated in the method, and the only field read is an
        // injected guard on an EvoSuite class, so the array store does not trigger impurity.
        assertFalse(methodMutatesSharedState.get("freshLocal()I"));
        // Impure: classic GETFIELD + IASTORE pattern that the heuristic targets.
        assertTrue(methodMutatesSharedState.get("fieldArray()I"));
        // Impure: allocating an array does not make a store into a parameter or a field pure.
        assertTrue(methodMutatesSharedState.get("mixedParameter([I)I"));
        assertTrue(methodMutatesSharedState.get("mixedReassignedParameter([I)I"));
        // Impure: the stored-into array is not allocated in the method.
        assertTrue(methodMutatesSharedState.get("parameterArray([I)I"));
        assertTrue(methodMutatesSharedState.get("arrayFromCall()I"));
    }

    private static byte[] createArrayStorePurityClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String owner = "sample/ArrayPurity";

        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "data", "[I", null, null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitInsn(Opcodes.ICONST_1);
        constructor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, owner, "data", "[I");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor freshLocal = writer.visitMethod(Opcodes.ACC_PUBLIC, "freshLocal", "()I", null, null);
        freshLocal.visitCode();
        freshLocal.visitFieldInsn(Opcodes.GETSTATIC, "org/evosuite/Properties", "ARRAY_LIMIT", "I");
        freshLocal.visitInsn(Opcodes.POP);
        freshLocal.visitInsn(Opcodes.ICONST_1);
        freshLocal.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        freshLocal.visitVarInsn(Opcodes.ASTORE, 1);
        freshLocal.visitVarInsn(Opcodes.ALOAD, 1);
        freshLocal.visitInsn(Opcodes.ICONST_0);
        freshLocal.visitIntInsn(Opcodes.BIPUSH, 7);
        freshLocal.visitInsn(Opcodes.IASTORE);
        freshLocal.visitVarInsn(Opcodes.ALOAD, 1);
        freshLocal.visitInsn(Opcodes.ICONST_0);
        freshLocal.visitInsn(Opcodes.IALOAD);
        freshLocal.visitInsn(Opcodes.IRETURN);
        freshLocal.visitMaxs(0, 0);
        freshLocal.visitEnd();

        // int[] local = new int[1]; local[0] = 1; input[0] = 2; return local[0];
        MethodVisitor mixed = writer.visitMethod(Opcodes.ACC_PUBLIC, "mixedParameter", "([I)I", null, null);
        mixed.visitCode();
        mixed.visitInsn(Opcodes.ICONST_1);
        mixed.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        mixed.visitVarInsn(Opcodes.ASTORE, 2);
        mixed.visitVarInsn(Opcodes.ALOAD, 2);
        mixed.visitInsn(Opcodes.ICONST_0);
        mixed.visitInsn(Opcodes.ICONST_1);
        mixed.visitInsn(Opcodes.IASTORE);
        mixed.visitVarInsn(Opcodes.ALOAD, 1);
        mixed.visitInsn(Opcodes.ICONST_0);
        mixed.visitInsn(Opcodes.ICONST_2);
        mixed.visitInsn(Opcodes.IASTORE);
        mixed.visitVarInsn(Opcodes.ALOAD, 2);
        mixed.visitInsn(Opcodes.ICONST_0);
        mixed.visitInsn(Opcodes.IALOAD);
        mixed.visitInsn(Opcodes.IRETURN);
        mixed.visitMaxs(0, 0);
        mixed.visitEnd();

        // int[] a = new int[1]; if (input != null) { a = input; } a[0] = 1; return a[0];
        MethodVisitor reassigned = writer.visitMethod(Opcodes.ACC_PUBLIC, "mixedReassignedParameter", "([I)I", null, null);
        reassigned.visitCode();
        reassigned.visitInsn(Opcodes.ICONST_1);
        reassigned.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        reassigned.visitVarInsn(Opcodes.ASTORE, 2);
        reassigned.visitVarInsn(Opcodes.ALOAD, 1);
        org.objectweb.asm.Label skip = new org.objectweb.asm.Label();
        reassigned.visitJumpInsn(Opcodes.IFNULL, skip);
        reassigned.visitVarInsn(Opcodes.ALOAD, 1);
        reassigned.visitVarInsn(Opcodes.ASTORE, 2);
        reassigned.visitLabel(skip);
        reassigned.visitVarInsn(Opcodes.ALOAD, 2);
        reassigned.visitInsn(Opcodes.ICONST_0);
        reassigned.visitInsn(Opcodes.ICONST_1);
        reassigned.visitInsn(Opcodes.IASTORE);
        reassigned.visitVarInsn(Opcodes.ALOAD, 2);
        reassigned.visitInsn(Opcodes.ICONST_0);
        reassigned.visitInsn(Opcodes.IALOAD);
        reassigned.visitInsn(Opcodes.IRETURN);
        reassigned.visitMaxs(0, 0);
        reassigned.visitEnd();

        MethodVisitor fieldArray = writer.visitMethod(Opcodes.ACC_PUBLIC, "fieldArray", "()I", null, null);
        fieldArray.visitCode();
        fieldArray.visitVarInsn(Opcodes.ALOAD, 0);
        fieldArray.visitFieldInsn(Opcodes.GETFIELD, owner, "data", "[I");
        fieldArray.visitInsn(Opcodes.ICONST_0);
        fieldArray.visitInsn(Opcodes.ICONST_3);
        fieldArray.visitInsn(Opcodes.IASTORE);
        fieldArray.visitVarInsn(Opcodes.ALOAD, 0);
        fieldArray.visitFieldInsn(Opcodes.GETFIELD, owner, "data", "[I");
        fieldArray.visitInsn(Opcodes.ICONST_0);
        fieldArray.visitInsn(Opcodes.IALOAD);
        fieldArray.visitInsn(Opcodes.IRETURN);
        fieldArray.visitMaxs(0, 0);
        fieldArray.visitEnd();

        MethodVisitor parameterArray = writer.visitMethod(Opcodes.ACC_PUBLIC, "parameterArray", "([I)I", null, null);
        parameterArray.visitCode();
        parameterArray.visitVarInsn(Opcodes.ALOAD, 1);
        parameterArray.visitInsn(Opcodes.ICONST_0);
        parameterArray.visitInsn(Opcodes.ICONST_5);
        parameterArray.visitInsn(Opcodes.IASTORE);
        parameterArray.visitVarInsn(Opcodes.ALOAD, 1);
        parameterArray.visitInsn(Opcodes.ICONST_0);
        parameterArray.visitInsn(Opcodes.IALOAD);
        parameterArray.visitInsn(Opcodes.IRETURN);
        parameterArray.visitMaxs(0, 0);
        parameterArray.visitEnd();

        MethodVisitor getSharedArray = writer.visitMethod(Opcodes.ACC_PRIVATE, "getSharedArray", "()[I", null, null);
        getSharedArray.visitCode();
        getSharedArray.visitVarInsn(Opcodes.ALOAD, 0);
        getSharedArray.visitFieldInsn(Opcodes.GETFIELD, owner, "data", "[I");
        getSharedArray.visitInsn(Opcodes.ARETURN);
        getSharedArray.visitMaxs(0, 0);
        getSharedArray.visitEnd();

        MethodVisitor arrayFromCall = writer.visitMethod(Opcodes.ACC_PUBLIC, "arrayFromCall", "()I", null, null);
        arrayFromCall.visitCode();
        arrayFromCall.visitVarInsn(Opcodes.ALOAD, 0);
        arrayFromCall.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "getSharedArray", "()[I", false);
        arrayFromCall.visitVarInsn(Opcodes.ASTORE, 1);
        arrayFromCall.visitVarInsn(Opcodes.ALOAD, 1);
        arrayFromCall.visitInsn(Opcodes.ICONST_0);
        arrayFromCall.visitIntInsn(Opcodes.BIPUSH, 9);
        arrayFromCall.visitInsn(Opcodes.IASTORE);
        arrayFromCall.visitVarInsn(Opcodes.ALOAD, 1);
        arrayFromCall.visitInsn(Opcodes.ICONST_0);
        arrayFromCall.visitInsn(Opcodes.IALOAD);
        arrayFromCall.visitInsn(Opcodes.IRETURN);
        arrayFromCall.visitMaxs(0, 0);
        arrayFromCall.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
