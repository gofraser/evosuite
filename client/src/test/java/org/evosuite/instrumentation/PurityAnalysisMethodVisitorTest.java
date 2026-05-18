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

        // Pure: no field loads, so the array store does not trigger impurity.
        assertFalse(methodMutatesSharedState.get("freshLocal()I"));
        // Impure: classic GETFIELD + IASTORE pattern that the heuristic targets.
        assertTrue(methodMutatesSharedState.get("fieldArray()I"));
        // Known limitations of the streaming heuristic: parameter mutation and
        // arrays obtained from method calls are not flagged because the method
        // body itself contains no GETFIELD/GETSTATIC. A precise flow-based
        // check would catch these but would require buffering the full method.
        assertFalse(methodMutatesSharedState.get("parameterArray([I)I"));
        assertFalse(methodMutatesSharedState.get("arrayFromCall()I"));
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
