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
package org.evosuite.instrumentation.error;

import org.evosuite.Properties;
import org.evosuite.runtime.instrumentation.AnnotatedLabel;
import org.evosuite.runtime.instrumentation.AnnotatedMethodNode;
import org.evosuite.utils.ArrayUtil;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * ErrorConditionMethodAdapter class.
 * </p>
 *
 * @author Gordon Fraser
 */
public class ErrorConditionMethodAdapter extends GeneratorAdapter {

    protected static final Logger logger = LoggerFactory.getLogger(ErrorConditionMethodAdapter.class);

    private final String className;

    private final String methodName;

    private final MethodVisitor next;

    protected List<ErrorBranchInstrumenter> instrumentation;


    /**
     * <p>
     * Constructor for ErrorConditionMethodAdapter.
     * </p>
     *
     * @param mv         a {@link org.objectweb.asm.MethodVisitor} object.
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param access     a int.
     * @param desc       a {@link java.lang.String} object.
     */
    public ErrorConditionMethodAdapter(MethodVisitor mv, String className,
                                       String methodName, int access, String desc) {
        super(Opcodes.ASM9,
                new AnnotatedMethodNode(access, methodName, desc, null, null), access,
                methodName, desc);
        this.className = className;
        this.methodName = methodName;
        next = mv;
        initErrorBranchInstrumenters();
    }

    private void initErrorBranchInstrumenters() {
        instrumentation = new ArrayList<>();
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.ARRAY)) {
            instrumentation.add(new ArrayInstrumentation(this));
        }
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.LIST)) {
            instrumentation.add(new ListInstrumentation(this));
            instrumentation.add(new LinkedListInstrumentation(this));
        }
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.CAST)) {
            instrumentation.add(new CastErrorInstrumentation(this));
        }
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.DEQUE)) {
            instrumentation.add(new DequeInstrumentation(this));
        }
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.DIVISIONBYZERO)) {
            instrumentation.add(new DivisionByZeroInstrumentation(this));
        }
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.LINKEDHASHSET)) {
            instrumentation.add(new LinkedHashSetInstrumentation(this));
        }
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.NPE)) {
            instrumentation.add(new NullPointerExceptionInstrumentation(this));
        }
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.OVERFLOW)) {
            instrumentation.add(new OverflowInstrumentation(this));
        }
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.QUEUE)) {
            instrumentation.add(new QueueInstrumentation(this));
        }
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.STACK)) {
            instrumentation.add(new StackInstrumentation(this));
        }
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.VECTOR)) {
            instrumentation.add(new VectorInstrumentation(this));
        }
        if (ArrayUtil.contains(Properties.ERROR_INSTRUMENTATION, Properties.ErrorInstrumentation.COLLECTIONCAPACITY)) {
            instrumentation.add(new CollectionCapacityInstrumentation(this));
        }
    }


    protected boolean inInstrumentation = false;

    @Override
    public void visitLabel(Label label) {
        if (label instanceof AnnotatedLabel) {
            AnnotatedLabel alabel = (AnnotatedLabel) label;
            inInstrumentation = alabel.isStartTag();
        }
        super.visitLabel(label);
    }

    /**
     * Tag branch.
     */
    public void tagBranch() {
        Label dummyTag = new AnnotatedLabel(false, true);
        super.visitLabel(dummyTag);
    }

    /**
     * Tag branch exit.
     */
    public void tagBranchExit() {
        Label dummyTag = new AnnotatedLabel(false, false);
        super.visitLabel(dummyTag);
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        if (!inInstrumentation) {
            inInstrumentation = true;
            for (ErrorBranchInstrumenter instrumenter : instrumentation) {
                instrumenter.visitMethodInsn(opcode, owner, name, desc, itf);
            }
            inInstrumentation = false;
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        if (!inInstrumentation) {
            inInstrumentation = true;
            for (ErrorBranchInstrumenter instrumenter : instrumentation) {
                instrumenter.visitFieldInsn(opcode, owner, name, desc);
            }
            inInstrumentation = false;
        }
        super.visitFieldInsn(opcode, owner, name, desc);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        if (!inInstrumentation) {
            inInstrumentation = true;
            for (ErrorBranchInstrumenter instrumenter : instrumentation) {
                instrumenter.visitIntInsn(opcode, operand);
            }
            inInstrumentation = false;
        }
        super.visitIntInsn(opcode, operand);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitTypeInsn(int opcode, String type) {
        if (!inInstrumentation) {
            inInstrumentation = true;
            for (ErrorBranchInstrumenter instrumenter : instrumentation) {
                instrumenter.visitTypeInsn(opcode, type);
            }
            inInstrumentation = false;
        }
        super.visitTypeInsn(opcode, type);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitInsn(int opcode) {
        if (!inInstrumentation) {
            inInstrumentation = true;
            for (ErrorBranchInstrumenter instrumenter : instrumentation) {
                instrumenter.visitInsn(opcode);
            }
            inInstrumentation = false;
        }
        super.visitInsn(opcode);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        if (!inInstrumentation) {
            inInstrumentation = true;
            for (ErrorBranchInstrumenter instrumenter : instrumentation) {
                instrumenter.visitMultiANewArrayInsn(descriptor, numDimensions);
            }
            inInstrumentation = false;
        }
        super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitEnd() {
        MethodNode mn = (MethodNode) mv;
        mn.accept(next);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(maxStack + 4, maxLocals);
    }

}
