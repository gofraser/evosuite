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


import org.evosuite.PackageInfo;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * <p>
 * MethodCallReplacementMethodAdapter class.
 * </p>
 *
 * @author fraser
 */
public class MethodCallReplacementMethodAdapter extends GeneratorAdapter {

    private final String className;

    private final String superClassName;

    private boolean needToWaitForSuperConstructor = false;

    private boolean hasBeenInstrumented = false;

    /**
     * Objects whose {@code NEW} has been seen but whose constructor call has not. Needed because
     * javac only emits a {@code DUP} after the {@code NEW} if the constructed object is actually
     * used; when it is discarded there is a single uninitialized reference on the stack.
     */
    private final Deque<PendingNew> pendingNewInstances = new ArrayDeque<>();

    /**
     * True while the next instruction may still be the {@code DUP} belonging to the most recent
     * {@code NEW}.
     */
    private boolean expectingDupAfterNew = false;

    /**
     * <p>
     * Constructor for MethodCallReplacementMethodAdapter.
     * </p>
     *
     * @param mv             a {@link org.objectweb.asm.MethodVisitor} object.
     * @param className      a {@link java.lang.String} object.
     * @param superClassName a {@link java.lang.String} object.
     * @param methodName     a {@link java.lang.String} object.
     * @param access         a int.
     * @param desc           a {@link java.lang.String} object.
     */
    public MethodCallReplacementMethodAdapter(MethodVisitor mv, String className,
                                              String superClassName, String methodName, int access, String desc) {
        super(Opcodes.ASM9, mv, access, methodName, desc);
        this.className = className;
        this.superClassName = superClassName;
        if (methodName.equals("<init>")) {
            needToWaitForSuperConstructor = true;
        }
    }

    /**
     * <p>getNextVisitor.</p>
     *
     * @return a {@link org.objectweb.asm.MethodVisitor} object.
     */
    public MethodVisitor getNextVisitor() {
        return mv;
    }

    /* The instruction visitors below only need to keep track of whether a NEW was immediately
     * followed by a DUP. Anything else that is emitted ends the NEW/DUP window. */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitTypeInsn(int opcode, String type) {
        if (opcode == Opcodes.NEW) {
            pendingNewInstances.push(new PendingNew(type));
            super.visitTypeInsn(opcode, type);
            expectingDupAfterNew = true;
            return;
        }
        expectingDupAfterNew = false;
        super.visitTypeInsn(opcode, type);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitInsn(int opcode) {
        if (opcode == Opcodes.DUP && expectingDupAfterNew && !pendingNewInstances.isEmpty()) {
            pendingNewInstances.peek().duplicated = true;
        }
        expectingDupAfterNew = false;
        super.visitInsn(opcode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitIntInsn(int opcode, int operand) {
        expectingDupAfterNew = false;
        super.visitIntInsn(opcode, operand);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitVarInsn(int opcode, int var) {
        expectingDupAfterNew = false;
        super.visitVarInsn(opcode, var);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        expectingDupAfterNew = false;
        super.visitFieldInsn(opcode, owner, name, desc);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitJumpInsn(int opcode, Label label) {
        expectingDupAfterNew = false;
        super.visitJumpInsn(opcode, label);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitLdcInsn(Object value) {
        expectingDupAfterNew = false;
        super.visitLdcInsn(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitIincInsn(int var, int increment) {
        expectingDupAfterNew = false;
        super.visitIincInsn(var, increment);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        expectingDupAfterNew = false;
        super.visitTableSwitchInsn(min, max, dflt, labels);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        expectingDupAfterNew = false;
        super.visitLookupSwitchInsn(dflt, keys, labels);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitMultiANewArrayInsn(String desc, int numDimensions) {
        expectingDupAfterNew = false;
        super.visitMultiANewArrayInsn(desc, numDimensions);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bootstrapMethodHandle,
                                       Object... bootstrapMethodArguments) {
        expectingDupAfterNew = false;
        super.visitInvokeDynamicInsn(name, desc, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    /* (non-Javadoc)
     * @see org.objectweb.asm.MethodVisitor#visitMethodInsn(int, java.lang.String, java.lang.String, java.lang.String)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {

        boolean isReplaced = false;

        // Consume the matching pending NEW, if any. Constructor replacements need to know whether
        // the uninitialized reference was duplicated. Default to true so that call shapes we
        // cannot track (eg super()/this() calls, which have no NEW) keep the historic behaviour.
        boolean uninitialisedReferenceIsDuplicated = true;
        if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)
                && !pendingNewInstances.isEmpty() && pendingNewInstances.peek().type.equals(owner)) {
            uninitialisedReferenceIsDuplicated = pendingNewInstances.pop().duplicated;
        }
        expectingDupAfterNew = false;

        // Generic Swing/AWT fallbacks for methods not covered by the cache-based
        // replacements (e.g., subclass calls that don't match the exact owner).
        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "setDragEnabled".equals(name)
                && "(Z)V".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_setDragEnabledGeneric",
                    "(Ljava/lang/Object;Z)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "setDropTarget".equals(name)
                && "(Ljava/awt/dnd/DropTarget;)V".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_setDropTargetGeneric",
                    "(Ljava/lang/Object;Ljava/awt/dnd/DropTarget;)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "setMixingCutoutShape".equals(name)
                && "(Ljava/awt/Shape;)V".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_setMixingCutoutShapeGeneric",
                    "(Ljava/lang/Object;Ljava/awt/Shape;)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "setCursor".equals(name)
                && "(Ljava/awt/Cursor;)V".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_setCursorGeneric",
                    "(Ljava/lang/Object;Ljava/awt/Cursor;)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "remove".equals(name)
                && "(Ljava/awt/Component;)V".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_removeComponentGeneric",
                    "(Ljava/lang/Object;Ljava/awt/Component;)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "add".equals(name)
                && "(Ljava/awt/Component;)Ljava/awt/Component;".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_addComponentGeneric",
                    "(Ljava/lang/Object;Ljava/awt/Component;)Ljava/awt/Component;",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "add".equals(name)
                && "(Ljava/lang/String;Ljava/awt/Component;)Ljava/awt/Component;".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_addNameComponentGeneric",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/awt/Component;)Ljava/awt/Component;",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "add".equals(name)
                && "(Ljava/awt/Component;I)Ljava/awt/Component;".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_addComponentIndexGeneric",
                    "(Ljava/lang/Object;Ljava/awt/Component;I)Ljava/awt/Component;",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "add".equals(name)
                && "(Ljava/awt/Component;Ljava/lang/Object;)V".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_addComponentConstraintsGeneric",
                    "(Ljava/lang/Object;Ljava/awt/Component;Ljava/lang/Object;)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "add".equals(name)
                && "(Ljava/awt/Component;Ljava/lang/Object;I)V".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_addComponentConstraintsIndexGeneric",
                    "(Ljava/lang/Object;Ljava/awt/Component;Ljava/lang/Object;I)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "addTab".equals(name)
                && "(Ljava/lang/String;Ljava/awt/Component;)V".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_addTabTitleComponentGeneric",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/awt/Component;)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "addTab".equals(name)
                && "(Ljava/lang/String;Ljavax/swing/Icon;Ljava/awt/Component;)V".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_addTabTitleIconComponentGeneric",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljavax/swing/Icon;Ljava/awt/Component;)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "addTab".equals(name)
                && "(Ljava/lang/String;Ljavax/swing/Icon;Ljava/awt/Component;Ljava/lang/String;)V".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_addTabTitleIconComponentTipGeneric",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljavax/swing/Icon;Ljava/awt/Component;Ljava/lang/String;)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "insertTab".equals(name)
                && "(Ljava/lang/String;Ljavax/swing/Icon;Ljava/awt/Component;Ljava/lang/String;I)V".equals(desc)
                && isSwingAwtOrAppOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class),
                    "replacement_insertTabGeneric",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljavax/swing/Icon;Ljava/awt/Component;Ljava/lang/String;I)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        // Generic socket connect fallback for Socket subclasses (eg SSLSocket).
        // Cache-based replacements are owner-exact (java/net/Socket), so subclass
        // owners may otherwise bypass VNET and attempt real network connects.
        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "connect".equals(name)
                && "(Ljava/net/SocketAddress;)V".equals(desc)
                && isSocketLikeOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.java.net.SocketConnectDispatch.class),
                    "connect",
                    "(Ljava/net/Socket;Ljava/net/SocketAddress;)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        if (!isReplaced && opcode == Opcodes.INVOKEVIRTUAL
                && "connect".equals(name)
                && "(Ljava/net/SocketAddress;I)V".equals(desc)
                && isSocketLikeOwner(owner)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.java.net.SocketConnectDispatch.class),
                    "connect",
                    "(Ljava/net/Socket;Ljava/net/SocketAddress;I)V",
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        // Thread subclasses can compile unqualified sleep(...) calls to
        // INVOKESTATIC on the current class owner (eg TestClockLike.sleep).
        // Route these to MockThread.sleep so sleep-guarding remains active.
        if (!isReplaced
                && opcode == Opcodes.INVOKESTATIC
                && owner.equals(className)
                && isCurrentClassThreadSubclass()
                && "sleep".equals(name)
                && ("(J)V".equals(desc) || "(JI)V".equals(desc))) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.java.lang.MockThread.class),
                    "sleep",
                    desc,
                    false);
            hasBeenInstrumented = true;
            isReplaced = true;
        }

        // Static replacement methods
        // For invokespecial this can only be used if a constructor is called,
        // not for super calls because not all mock classes may be superclasses
        // of the actual object. E.g. Throwable -> Exception -> RuntimeException
        // A MockRuntimeException is not a subclass of MockException and MockThrowable
        if (!isReplaced
                && MethodCallReplacementCache.getInstance().hasReplacementCall(owner, name + desc)
                && (opcode != Opcodes.INVOKESPECIAL || name.equals("<init>"))
                && !isPendingSuperOrThisConstructorCall(opcode, owner, name)) {
            MethodCallReplacement replacement = MethodCallReplacementCache.getInstance().getReplacementCall(owner,
                    name + desc);
            isReplaced = true;
            replacement.insertMethodCall(this, Opcodes.INVOKESTATIC, opcode, itf,
                    uninitialisedReferenceIsDuplicated);
            hasBeenInstrumented = true;
        }

        // for constructors
        if (!isReplaced) {
            if (MethodCallReplacementCache.getInstance().hasSpecialReplacementCall(owner, name + desc)) {
                MethodCallReplacement replacement = MethodCallReplacementCache.getInstance()
                        .getSpecialReplacementCall(owner, name + desc);
                if (replacement.isTarget(owner, name, desc)
                        && opcode == Opcodes.INVOKESPECIAL && name.equals("<init>")) {
                    isReplaced = true;
                    hasBeenInstrumented = true;
                    boolean isSelf = false;
                    if (needToWaitForSuperConstructor) {
                        String originalClassNameWithDots = owner.replace('/', '.');
                        if (originalClassNameWithDots.equals(superClassName)) {
                            isSelf = true;
                        }
                    }
                    if (replacement.getMethodName().equals("<init>")) {
                        replacement.insertConstructorCall(this, replacement, isSelf);
                    } else {
                        replacement.insertMethodCall(this, Opcodes.INVOKESPECIAL, opcode, itf,
                                uninitialisedReferenceIsDuplicated);
                    }
                }
            }
        }

        // non-static replacement methods
        //        if (!isReplaced) {
        //            iterator = MethodCallReplacementCache.getInstance().getVirtualReplacementCalls();
        //            while(iterator.hasNext()) {
        //                MethodCallReplacement replacement = iterator.next();
        //                if (replacement.isTarget(owner, name, desc)) {
        //                    isReplaced = true;
        //                    replacement.insertMethodCall(this, Opcodes.INVOKEVIRTUAL);
        //                    break;
        //                }
        //            }
        //        }

        if (!isReplaced) {
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }
        if (needToWaitForSuperConstructor) {
            if (opcode == Opcodes.INVOKESPECIAL) {
                String originalClassNameWithDots = owner.replace('/', '.');
                if (originalClassNameWithDots.equals(superClassName)) {
                    needToWaitForSuperConstructor = false;
                }
            }
        }
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // The instrumentation adds a boolean to the stack at one point
        // which _may_ increase the max stack size. A ASM
        // doesn't manage to calculate the maximum stack size
        // correctly we just add one here
        if (hasBeenInstrumented) {
            super.visitMaxs(maxStack + 1, maxLocals);
        } else {
            super.visitMaxs(maxStack, maxLocals);
        }
    }

    private static boolean isSwingAwtOrAppOwner(String owner) {
        if (owner.startsWith("javax/swing/") || owner.startsWith("java/awt/")) {
            return true;
        }
        // Also support project-specific wrappers/subclasses (eg custom JList subclasses).
        // Keep JDK/Javax non-Swing owners excluded to avoid broad accidental rewrites.
        return !(owner.startsWith("java/") || owner.startsWith("javax/"));
    }

    private static boolean isSocketLikeOwner(String owner) {
        return "java/net/Socket".equals(owner) || "javax/net/ssl/SSLSocket".equals(owner);
    }

    private boolean isCurrentClassThreadSubclass() {
        return "java.lang.Thread".equals(superClassName);
    }

    /**
     * During constructor prologue, calls to {@code super(...)} / {@code this(...)} do not have
     * the NEW+DUP stack shape. Replacing those constructor calls with static factories would
     * corrupt stack bookkeeping and can crash ASM advice visitors.
     */
    private boolean isPendingSuperOrThisConstructorCall(int opcode, String owner, String name) {
        if (opcode != Opcodes.INVOKESPECIAL || !"<init>".equals(name) || !needToWaitForSuperConstructor) {
            return false;
        }
        String ownerWithDots = owner.replace('/', '.');
        return ownerWithDots.equals(superClassName) || ownerWithDots.equals(className);
    }

    /**
     * An object allocated by a {@code NEW} whose constructor has not been called yet.
     */
    private static final class PendingNew {

        private final String type;

        private boolean duplicated = false;

        private PendingNew(String type) {
            this.type = type;
        }
    }
}
