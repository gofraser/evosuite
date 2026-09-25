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

import org.evosuite.PackageInfo;
import org.evosuite.Properties;
import org.evosuite.classpath.ResourceList;
import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.setup.DependencyAnalysis;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.HashMap;
import java.util.Map;


/**
 * <p>ArrayAllocationLimitMethodAdapter class.</p>
 *
 * @author Gordon Fraser
 */
public class ArrayAllocationLimitMethodAdapter extends GeneratorAdapter {

    /**
     * Whether the per-allocation array-size guard is emitted for this class.
     *
     * <p>{@code array_limit} bounds the array sizes a <em>test</em> can ask for, so it only makes
     * sense where the size can come from a test input: the classes under analysis. Third-party
     * library code sizes its own buffers from its own constants -- {@code jxl} allocates a
     * 5 MB workbook buffer from {@code WorkbookSettings.initialFileSize}, five times the default
     * limit -- and rejecting that is a false positive that kills every call into the library.
     * EvoSuite 1.2.0 chained this adapter from {@code ExecutionPathClassAdapter} only, i.e. it
     * had exactly this scope; it was later also chained from {@code NonTargetClassAdapter}.
     *
     * <p>The cumulative budget deliberately does <em>not</em> share this scope: see
     * {@link #emitCumulativeBudgetCheck()}.
     */
    private final boolean sizeGuardEnabled;

    /**
     * <p>Constructor for ArrayAllocationLimitMethodAdapter.</p>
     *
     * @param mv         a {@link org.objectweb.asm.MethodVisitor} object.
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param access     a int.
     * @param desc       a {@link java.lang.String} object.
     */
    public ArrayAllocationLimitMethodAdapter(MethodVisitor mv, String className,
                                             String methodName, int access, String desc) {
        super(Opcodes.ASM9, mv, access, methodName, desc);
        this.sizeGuardEnabled = appliesTo(className);
    }

    private static boolean appliesTo(String className) {
        if (className == null) {
            return false;
        }
        try {
            return DependencyAnalysis.shouldAnalyze(
                    ResourceList.getClassNameFromResourcePath(className));
        } catch (Throwable t) {
            // Analysis state not available (e.g. in isolated instrumentation tests): keep the
            // guard rather than silently dropping it.
            return true;
        }
    }


    /**
     * Emits {@code throw new TooManyResourcesException(message)}.
     *
     * <p>Refusing an allocation is <em>our</em> decision, not the execution watchdog firing, so
     * it must not be reported as {@code TestCaseExecutor.TimeoutExceeded}: that marks the whole
     * test as timed out, gives it worst-case fitness, feeds the "disable functional mocking after
     * N consecutive timeouts" heuristic with timeouts that never happened, and makes the JUnit
     * re-check drop the test as unstable. {@code TooManyResourcesException} already means "the
     * test asked for more resources than we are willing to give it" and is understood end-to-end
     * (assertion generation, JUnit re-checks, generated test code).
     */
    private void throwAllocationRejected(String message) {
        super.visitTypeInsn(Opcodes.NEW,
                PackageInfo.getNameWithSlash(TooManyResourcesException.class));
        super.visitInsn(Opcodes.DUP);
        super.visitLdcInsn(message);
        super.visitMethodInsn(Opcodes.INVOKESPECIAL,
                PackageInfo.getNameWithSlash(TooManyResourcesException.class),
                "<init>", "(Ljava/lang/String;)V", false);
        super.visitInsn(Opcodes.ATHROW);
    }

    /* (non-Javadoc)
     * @see org.objectweb.asm.MethodVisitor#visitIntInsn(int, int)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitIntInsn(int opcode, int operand) {
        if (opcode == Opcodes.NEWARRAY) {
            if (sizeGuardEnabled) {
                Label origTarget = new Label();
                visitInsn(Opcodes.DUP);
                visitFieldInsn(Opcodes.GETSTATIC, PackageInfo.getNameWithSlash(org.evosuite.Properties.class),
                        "ARRAY_LIMIT", "I");
                super.visitJumpInsn(Opcodes.IF_ICMPLE, origTarget);
                throwAllocationRejected("Array allocation exceeds the configured array_limit");
                super.visitLabel(origTarget);
            }
            emitCumulativeBudgetCheck();
        }
        super.visitIntInsn(opcode, operand);
    }

    /* (non-Javadoc)
     * @see org.objectweb.asm.MethodVisitor#visitTypeInsn(int, java.lang.String)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitTypeInsn(int opcode, String type) {

        if (opcode == Opcodes.ANEWARRAY) {
            if (sizeGuardEnabled) {
                Label origTarget = new Label();
                visitInsn(Opcodes.DUP);
                visitFieldInsn(Opcodes.GETSTATIC, PackageInfo.getNameWithSlash(org.evosuite.Properties.class),
                        "ARRAY_LIMIT", "I");
                super.visitJumpInsn(Opcodes.IF_ICMPLE, origTarget);
                throwAllocationRejected("Array allocation exceeds the configured array_limit");
                super.visitLabel(origTarget);
            }
            emitCumulativeBudgetCheck();
        }
        super.visitTypeInsn(opcode, type);
    }

    /**
     * Emits a call that records the array size still sitting on top of the stack against
     * the cumulative allocation budget, and rejects the allocation if that running total
     * is now too high. Leaves the stack exactly as it found it (the size is duplicated,
     * not consumed) so the NEWARRAY/ANEWARRAY that follows still sees its operand.
     *
     * <p>A single allocation passing the per-call {@code array_limit} check above says
     * nothing about a loop that repeats such an allocation many times -- e.g. one chunk
     * per iteration in a chunked-array growth method -- which is exactly the pattern that
     * can exhaust the heap while every individual allocation looks legal. See
     * {@link org.evosuite.runtime.ArrayAllocationBudget}.
     *
     * <p>Unlike the per-allocation guard this is emitted for <em>every</em> instrumented
     * class, not only the ones under analysis. The reason the size guard is scoped
     * ({@link #sizeGuardEnabled}) is that it rejects one legitimately large buffer a library
     * sizes from its own constant; the cumulative budget cannot make that mistake, because
     * such a buffer is a few million elements against a per-test total of
     * {@code cumulative_array_limit} (50M by default) that only a runaway accumulation
     * reaches. Scoping it to the class under test left the common case unguarded: 44_summa's
     * DoubleIntArrayList is the target and is guarded, but it delegates to ChunkedLongArray,
     * which is not, and that is where the {@code while (chunks.size() <= index >>> 20)
     * chunks.add(new long[1 << 20])} loop allocates 8 MB a time until the heap is gone. All
     * 19 runs that died of low memory in the 2026-09-24 SF110 replication threw no
     * TooManyResourcesException at all.
     */
    private void emitCumulativeBudgetCheck() {
        visitInsn(Opcodes.DUP);
        visitInsn(Opcodes.I2L);
        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                PackageInfo.getNameWithSlash(org.evosuite.runtime.ArrayAllocationBudget.class),
                "checkAndRecord", "(J)V", false);
    }

    /**
     * Intercept calls to JDK factory methods that internally allocate large arrays,
     * bypassing the NEWARRAY/ANEWARRAY instrumentation. The size argument on the stack
     * is clamped to ARRAY_LIMIT before the call proceeds.
     */
    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                boolean isInterface) {
        if (sizeGuardEnabled && shouldLimitSizeArg(opcode, owner, name, descriptor)) {
            // Stack: ..., int(size)  →  clamp size to ARRAY_LIMIT
            Label ok = new Label();
            visitInsn(Opcodes.DUP);
            visitFieldInsn(Opcodes.GETSTATIC, PackageInfo.getNameWithSlash(Properties.class),
                    "ARRAY_LIMIT", "I");
            super.visitJumpInsn(Opcodes.IF_ICMPLE, ok);
            // size > ARRAY_LIMIT → reject the allocation
            throwAllocationRejected("Array allocation exceeds the configured array_limit");
            super.visitLabel(ok);
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    private static boolean shouldLimitSizeArg(int opcode, String owner, String name,
                                              String descriptor) {
        // ByteBuffer.allocate(int) and ByteBuffer.allocateDirect(int)
        if (opcode == Opcodes.INVOKESTATIC && "java/nio/ByteBuffer".equals(owner)
                && ("allocate".equals(name) || "allocateDirect".equals(name))
                && "(I)Ljava/nio/ByteBuffer;".equals(descriptor)) {
            return true;
        }
        // StringBuilder/StringBuffer initial capacity
        if ((opcode == Opcodes.INVOKESPECIAL)
                && ("java/lang/StringBuilder".equals(owner) || "java/lang/StringBuffer".equals(owner))
                && "<init>".equals(name) && "(I)V".equals(descriptor)) {
            return true;
        }
        return false;
    }

    /* (non-Javadoc)
     * @see org.objectweb.asm.MethodVisitor#visitMultiANewArrayInsn(java.lang.String, int)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {

        if (!sizeGuardEnabled) {
            // No cumulative accounting here yet: the dimensions are consumed by the
            // allocation itself, so recording the product needs the same spill-to-locals
            // dance as the checks below. A loop of multi-dimensional allocations in a
            // library class is therefore still unguarded.
            super.visitMultiANewArrayInsn(desc, dims);
            return;
        }

        Label origTarget = new Label();
        Label errorTarget = new Label();

        // Multidimensional arrays can only have max 256 dimensions
        if (Properties.ARRAY_LIMIT < 256) {
            push(dims);
            visitFieldInsn(Opcodes.GETSTATIC, PackageInfo.getNameWithSlash(org.evosuite.Properties.class),
                    "ARRAY_LIMIT", "I");
            super.visitJumpInsn(Opcodes.IF_ICMPGT, errorTarget);
        }

        // Check each of the dimensions
        Map<Integer, Integer> to = new HashMap<>();
        for (int i = dims - 1; i >= 0; i--) {
            int loc = newLocal(Type.INT_TYPE);
            storeLocal(loc);
            to.put(i, loc);
        }
        for (int i = 0; i < dims; i++) {
            loadLocal(to.get(i));
            visitFieldInsn(Opcodes.GETSTATIC, "org/evosuite/Properties",
                    "ARRAY_LIMIT", "I");
            super.visitJumpInsn(Opcodes.IF_ICMPGT, errorTarget);
        }

        // Check the product of all dimensions against ARRAY_LIMIT.
        // Individual dimensions may each be under the limit, but their
        // product can still cause OOM (e.g. new Float[50000][50000]).
        if (dims >= 2) {
            // Compute product as long to avoid int overflow
            loadLocal(to.get(0));
            visitInsn(Opcodes.I2L);
            for (int i = 1; i < dims; i++) {
                loadLocal(to.get(i));
                visitInsn(Opcodes.I2L);
                visitInsn(Opcodes.LMUL);
            }
            // Compare: product >= (long) ARRAY_LIMIT → reject
            visitFieldInsn(Opcodes.GETSTATIC, PackageInfo.getNameWithSlash(Properties.class),
                    "ARRAY_LIMIT", "I");
            visitInsn(Opcodes.I2L);
            visitInsn(Opcodes.LCMP);
            // LCMP pushes 1 if product > limit, 0 if equal, -1 if less
            super.visitJumpInsn(Opcodes.IFGT, errorTarget);
        }

        goTo(origTarget);
        super.visitLabel(errorTarget);
        throwAllocationRejected("Multi-dimensional array allocation exceeds the configured array_limit");
        super.visitLabel(origTarget);

        // Restore original dimensions
        for (int i = 0; i < dims; i++) {
            loadLocal(to.get(i));
        }

        super.visitMultiANewArrayInsn(desc, dims);
    }

}
