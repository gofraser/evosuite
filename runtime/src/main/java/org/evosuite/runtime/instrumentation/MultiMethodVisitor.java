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

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

/**
 * MethodVisitor that acts as a proxy to two other visitors.
 *
 * @author Gordon Fraser
 */
public class MultiMethodVisitor extends MethodVisitor {

    MethodVisitor mv1;
    MethodVisitor mv2;

    private final Map<Label, Label> labelMapping = new HashMap<>();

    public MultiMethodVisitor(MethodVisitor mv1, MethodVisitor mv2) {
        super(Opcodes.ASM9);
        this.mv1 = mv1;
        this.mv2 = mv2;
    }

    private Label getLabel(Label l) {
        if (labelMapping.containsKey(l)) {
            return labelMapping.get(l);
        } else {
            Label l2 = new Label();
            labelMapping.put(l, l2);
            return l2;
        }

    }

    @Override
    public AnnotationVisitor visitAnnotation(String arg0, boolean arg1) {
        mv1.visitAnnotation(arg0, arg1);
        return mv2.visitAnnotation(arg0, arg1);
    }

    @Override
    public AnnotationVisitor visitAnnotationDefault() {
        mv1.visitAnnotationDefault();
        return mv2.visitAnnotationDefault();
    }

    @Override
    public void visitAttribute(Attribute arg0) {
        mv1.visitAttribute(arg0);
        mv2.visitAttribute(arg0);
    }

    @Override
    public void visitCode() {
        mv1.visitCode();
        mv2.visitCode();
    }

    @Override
    public void visitEnd() {
        mv1.visitEnd();
        mv2.visitEnd();
    }

    @Override
    public void visitFieldInsn(int arg0, String arg1, String arg2, String arg3) {
        mv1.visitFieldInsn(arg0, arg1, arg2, arg3);
        mv2.visitFieldInsn(arg0, arg1, arg2, arg3);
    }

    @Override
    public void visitFrame(int arg0, int arg1, Object[] arg2, int arg3, Object[] arg4) {
        mv1.visitFrame(arg0, arg1, arg2, arg3, arg4);
        mv2.visitFrame(arg0, arg1, arg2, arg3, arg4);

    }

    @Override
    public void visitIincInsn(int arg0, int arg1) {
        mv1.visitIincInsn(arg0, arg1);
        mv2.visitIincInsn(arg0, arg1);
    }

    @Override
    public void visitInsn(int arg0) {
        mv1.visitInsn(arg0);
        mv2.visitInsn(arg0);
    }

    @Override
    public void visitIntInsn(int arg0, int arg1) {
        mv1.visitIntInsn(arg0, arg1);
        mv2.visitIntInsn(arg0, arg1);
    }

    @Override
    public void visitJumpInsn(int arg0, Label arg1) {
        mv1.visitJumpInsn(arg0, arg1);
        mv2.visitJumpInsn(arg0, getLabel(arg1));
    }

    @Override
    public void visitLabel(Label arg0) {
        mv1.visitLabel(arg0);
        mv2.visitLabel(getLabel(arg0));
    }

    @Override
    public void visitLdcInsn(Object arg0) {
        mv1.visitLdcInsn(arg0);
        mv2.visitLdcInsn(arg0);
    }

    @Override
    public void visitLineNumber(int arg0, Label arg1) {
        mv1.visitLineNumber(arg0, arg1);
    }

    @Override
    public void visitLocalVariable(String arg0, String arg1, String arg2, Label arg3,
                                   Label arg4, int arg5) {
        mv1.visitLocalVariable(arg0, arg1, arg2, arg3, arg4, arg5);
        mv2.visitLocalVariable(arg0, arg1, arg2, getLabel(arg3), getLabel(arg4),
                arg5);
    }

    @Override
    public void visitLookupSwitchInsn(Label arg0, int[] arg1, Label[] arg2) {
        mv1.visitLookupSwitchInsn(arg0, arg1, arg2);
        Label[] arg2Copy = new Label[arg2.length];
        for (int i = 0; i < arg2.length; i++) {
            arg2Copy[i] = getLabel(arg2[i]);
        }

        mv2.visitLookupSwitchInsn(getLabel(arg0), arg1,
                arg2Copy);
    }

    @Override
    public void visitMaxs(int arg0, int arg1) {
        mv1.visitMaxs(arg0, arg1);
        mv2.visitMaxs(arg0, arg1);
    }

    @Override
    public void visitMethodInsn(int arg0, String arg1, String arg2, String arg3, boolean itf) {
        mv1.visitMethodInsn(arg0, arg1, arg2, arg3, itf);
        mv2.visitMethodInsn(arg0, arg1, arg2, arg3, itf);
    }

    @Override
    public void visitMethodInsn(int arg0, String arg1, String arg2, String arg3) {
        mv1.visitMethodInsn(arg0, arg1, arg2, arg3);
        mv2.visitMethodInsn(arg0, arg1, arg2, arg3);
    }

    @Override
    public void visitMultiANewArrayInsn(String arg0, int arg1) {
        mv1.visitMultiANewArrayInsn(arg0, arg1);
        mv2.visitMultiANewArrayInsn(arg0, arg1);
    }


    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
        mv1.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
        mv2.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int arg0, String arg1,
                                                      boolean arg2) {
        mv1.visitParameterAnnotation(arg0, arg1, arg2);
        return mv2.visitParameterAnnotation(arg0, arg1, arg2);
    }

    @Override
    public void visitTableSwitchInsn(int arg0, int arg1, Label arg2, Label... arg3) {
        mv1.visitTableSwitchInsn(arg0, arg1, arg2, arg3);
        Label[] arg3Copy = new Label[arg3.length];
        for (int i = 0; i < arg3.length; i++) {
            arg3Copy[i] = getLabel(arg3[i]);
        }
        mv2.visitTableSwitchInsn(arg0, arg1, getLabel(arg2),
                arg3Copy);
    }

    @Override
    public void visitTryCatchBlock(Label arg0, Label arg1, Label arg2, String arg3) {
        mv1.visitTryCatchBlock(arg0, arg1, arg2, arg3);
        mv2.visitTryCatchBlock(getLabel(arg0), getLabel(arg1), getLabel(arg2), arg3);
    }

    @Override
    public void visitTypeInsn(int arg0, String arg1) {
        mv1.visitTypeInsn(arg0, arg1);
        mv2.visitTypeInsn(arg0, arg1);
    }

    @Override
    public void visitVarInsn(int arg0, int arg1) {
        mv1.visitVarInsn(arg0, arg1);
        mv2.visitVarInsn(arg0, arg1);
    }

}
