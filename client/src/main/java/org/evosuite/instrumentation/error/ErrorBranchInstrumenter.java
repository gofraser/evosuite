/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.instrumentation.error;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class ErrorBranchInstrumenter {

    protected static final Logger logger = LoggerFactory.getLogger(ErrorBranchInstrumenter.class);

    protected ErrorConditionMethodAdapter mv;

    protected String methodName;

    public ErrorBranchInstrumenter(ErrorConditionMethodAdapter mv) {
        this.mv = mv;
        this.methodName = mv.getMethodName();
    }

    /**
     * Get the callee of a method.
     *
     * @param desc the method descriptor.
     * @return a map of parameter indices to local variable indices.
     */
    public Map<Integer, Integer> getMethodCallee(String desc) {
        Type[] args = Type.getArgumentTypes(desc);
        Map<Integer, Integer> to = new HashMap<>();
        for (int i = args.length - 1; i >= 0; i--) {
            int loc = mv.newLocal(args[i]);
            mv.storeLocal(loc);
            to.put(i, loc);
        }

        mv.dup();//callee
        return to;
    }

    /**
     * Restore the parameters of a method.
     *
     * @param to   the map of parameter indices to local variable indices.
     * @param desc the method descriptor.
     */
    public void restoreMethodParameters(Map<Integer, Integer> to, String desc) {
        Type[] args = Type.getArgumentTypes(desc);

        for (int i = 0; i < args.length; i++) {
            mv.loadLocal(to.get(i));
        }
    }

    /**
     * Visit a method instruction.
     *
     * @param opcode the opcode.
     * @param owner  the owner class.
     * @param name   the method name.
     * @param desc   the method descriptor.
     */
    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
        throw new RuntimeException("This method should not be called since ASM5 API is used");
    }


    /**
     * Visit a method instruction.
     *
     * @param opcode the opcode.
     * @param owner  the owner class.
     * @param name   the method name.
     * @param desc   the method descriptor.
     * @param itf    whether the owner is an interface.
     */
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {

    }

    /**
     * Visit a field instruction.
     *
     * @param opcode the opcode.
     * @param owner  the owner class.
     * @param name   the field name.
     * @param desc   the field descriptor.
     */
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {

    }

    /**
     * Visit a type instruction.
     *
     * @param opcode the opcode.
     * @param type   the type.
     */
    public void visitTypeInsn(int opcode, String type) {

    }

    /**
     * Visit an instruction.
     *
     * @param opcode the opcode.
     */
    public void visitInsn(int opcode) {

    }

    /**
     * Visit an integer instruction.
     *
     * @param opcode  the opcode.
     * @param operand the operand.
     */
    public void visitIntInsn(int opcode,
                             int operand) {
    }

    /**
     * Visit a multi-array instruction.
     *
     * @param descriptor    the array descriptor.
     * @param numDimensions the number of dimensions.
     */
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {

    }


    protected void insertBranch(int opcode, String exception) {
        Label origTarget = new Label();
        mv.tagBranch();
        mv.visitJumpInsn(opcode, origTarget);
        mv.visitTypeInsn(Opcodes.NEW, exception);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, exception,
                "<init>", "()V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(origTarget);
        mv.tagBranchExit();
    }

    protected void insertBranchWithoutTag(int opcode, String exception) {
        Label origTarget = new Label();
        mv.visitJumpInsn(opcode, origTarget);
        mv.visitTypeInsn(Opcodes.NEW, exception);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, exception,
                "<init>", "()V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(origTarget);
    }

    protected void tagBranchStart() {
        mv.tagBranch();
    }

    protected void tagBranchEnd() {
        mv.tagBranchExit();
    }

}
