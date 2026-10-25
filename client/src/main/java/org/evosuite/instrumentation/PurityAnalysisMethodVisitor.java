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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

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
 *     <li>array-store opcodes (conservatively treated as field updates unless the
 *     stored-into array is provably allocated by the method itself)</li>
 * </ul>
 * This class only reads the existing bytecode. It streams every event on and, for
 * methods of moderate size, keeps a copy of the code so that array stores can be
 * checked with a data-flow analysis in <code>visitEnd</code>.
 *
 * @author Juan Galeotti
 */
public class PurityAnalysisMethodVisitor extends MethodVisitor {

    private boolean updatesField;
    private boolean hasArrayStore;
    /**
     * A method with more events than this is not copied; its array stores stay impure.
     */
    private static final int MAX_RECORDED_EVENTS = 10000;

    private MethodNode body;
    private int recordedEvents;
    private int maxVar;
    private int maxStack;
    private int maxLocals;
    private final String owner;
    private final CheapPurityAnalyzer purityAnalyzer;
    private final String classNameWithDots;
    private final String methodName;
    private final String descriptor;

    /**
     * <p>Constructor for PutStaticMethodAdapter.</p>
     *
     * @param className      a {@link java.lang.String} object.
     * @param access         the access flags of the method.
     * @param methodName     a {@link java.lang.String} object.
     * @param descriptor     a {@link java.lang.String} object.
     * @param mv             a {@link org.objectweb.asm.MethodVisitor} object.
     * @param purityAnalyzer a {@link CheapPurityAnalyzer} object.
     */
    public PurityAnalysisMethodVisitor(String className, int access, String methodName,
                                       String descriptor, MethodVisitor mv,
                                       CheapPurityAnalyzer purityAnalyzer) {
        super(Opcodes.ASM9, mv);
        this.body = new PrivateLabelsMethodNode(access, methodName, descriptor);
        this.owner = className;
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
        }
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitFieldInsn(opcode, owner, name, desc);
        }
        super.visitFieldInsn(opcode, owner, name, desc);
    }

    @Override
    public void visitInsn(int opcode) {
        if (isArrayStoreOpcode(opcode)) {
            hasArrayStore = true;
        }
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitInsn(opcode);
        }
        super.visitInsn(opcode);
    }

    private MethodNode recording() {
        if (body != null && ++recordedEvents > MAX_RECORDED_EVENTS) {
            body = null;
        }
        return body;
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitIntInsn(opcode, operand);
        }
        super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitVarInsn(opcode, var);
            maxVar = Math.max(maxVar, var);
        }
        super.visitVarInsn(opcode, var);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitTypeInsn(opcode, type);
        }
        super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                       Object... bootstrapMethodArguments) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitJumpInsn(opcode, label);
        }
        super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitLabel(Label label) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitLabel(label);
        }
        super.visitLabel(label);
    }

    @Override
    public void visitLdcInsn(Object value) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitLdcInsn(value);
        }
        super.visitLdcInsn(value);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitIincInsn(var, increment);
            maxVar = Math.max(maxVar, var);
        }
        super.visitIincInsn(var, increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitTableSwitchInsn(min, max, dflt, labels);
        }
        super.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitLookupSwitchInsn(dflt, keys, labels);
        }
        super.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitMultiANewArrayInsn(descriptor, numDimensions);
        }
        super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        this.maxStack = maxStack;
        this.maxLocals = maxLocals;
        super.visitMaxs(maxStack, maxLocals);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        MethodNode copy = recording();
        if (copy != null) {
            copy.visitTryCatchBlock(start, end, handler, type);
        }
        super.visitTryCatchBlock(start, end, handler, type);
    }

    public boolean updatesField() {
        return updatesField;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name,
                                String desc, boolean itf) {

        MethodNode copy = recording();
        if (copy != null) {
            copy.visitMethodInsn(opcode, owner, name, desc, itf);
        }
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
        // Fields read by the code are no longer a signal: an array store is a
        // field update unless the array is provably allocated by this method.
        if (!updatesField && hasArrayStore && !storesOnlyIntoFreshArrays()) {
            updatesField = true;
        }
        if (updatesField) {
            purityAnalyzer.addUpdatesFieldMethod(classNameWithDots, methodName, descriptor);
        }
        super.visitEnd();
    }

    /**
     * Checks that the array operand of every array store is a reference that
     * the method allocated itself, directly or through local variables whose
     * every assignment is such a reference. Parameters, fields, call results
     * and every shape the analysis cannot follow are not fresh.
     */
    private boolean storesOnlyIntoFreshArrays() {
        if (body == null) {
            return false;
        }
        body.maxLocals = Math.max(Math.max(maxLocals, maxVar + 2), parameterSlots());
        body.maxStack = maxStack;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, body);
        } catch (AnalyzerException | RuntimeException e) {
            return false;
        }
        AbstractInsnNode[] insns = body.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (isArrayStoreOpcode(insns[i].getOpcode())) {
                Frame<SourceValue> frame = frames[i];
                if (frame == null) {
                    continue; // unreachable
                }
                SourceValue array = frame.getStack(frame.getStackSize() - 3);
                if (!isFresh(array, frames, new HashSet<>())) {
                    return false;
                }
            }
        }
        return true;
    }

    private int parameterSlots() {
        int slots = (body.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            slots += argument.getSize();
        }
        return slots;
    }

    private boolean isFresh(SourceValue value, Frame<SourceValue>[] frames,
                            Set<AbstractInsnNode> visiting) {
        if (value.insns.isEmpty()) {
            return false; // a parameter or an uninitialised local
        }
        for (AbstractInsnNode source : value.insns) {
            if (!visiting.add(source)) {
                continue; // a cycle through locals adds no new origin
            }
            switch (source.getOpcode()) {
                case Opcodes.NEWARRAY:
                case Opcodes.ANEWARRAY:
                case Opcodes.MULTIANEWARRAY:
                    break;
                case Opcodes.ALOAD:
                    // A parameter slot is not fresh even when reassigned: the merge
                    // of the incoming argument with a later assignment would hide it.
                    int var = ((VarInsnNode) source).var;
                    if (var < parameterSlots()
                            || !isFresh(frames[body.instructions.indexOf(source)].getLocal(var),
                            frames, visiting)) {
                        return false;
                    }
                    break;
                case Opcodes.ASTORE:
                case Opcodes.DUP: {
                    Frame<SourceValue> frame = frames[body.instructions.indexOf(source)];
                    if (frame == null
                            || !isFresh(frame.getStack(frame.getStackSize() - 1), frames, visiting)) {
                        return false;
                    }
                    break;
                }
                default:
                    return false;
            }
        }
        return true;
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

    /**
     * A copy that keeps its label nodes to itself. {@link MethodNode} normally
     * keeps them in {@link Label#info}, but the labels here belong to the code
     * being instrumented, and their {@code info} is already in use: an
     * {@code AnnotatedMethodNode} keeps its own label nodes there, and method
     * call replacement tags labels with it. Linking the tree's nodes into this
     * copy corrupted the replay, and the class writer met a jump target without
     * a frame.
     */
    private static final class PrivateLabelsMethodNode extends MethodNode {

        private final Map<Label, LabelNode> labelNodes = new IdentityHashMap<>();

        PrivateLabelsMethodNode(int access, String name, String descriptor) {
            super(Opcodes.ASM9, access, name, descriptor, null, null);
        }

        @Override
        protected LabelNode getLabelNode(Label label) {
            return labelNodes.computeIfAbsent(label, l -> new LabelNode());
        }
    }
}
