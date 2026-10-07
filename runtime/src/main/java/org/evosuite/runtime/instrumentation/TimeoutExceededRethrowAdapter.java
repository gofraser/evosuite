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

import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A minimal {@link MethodVisitor} that prevents SUT catch blocks from
 * swallowing EvoSuite's {@code TestCaseExecutor.TimeoutExceeded} exception.
 *
 * <p>At every catch handler entry point whose declared type could
 * catch {@code TimeoutExceeded} (i.e. {@code Throwable}, {@code Exception},
 * or {@code RuntimeException}), this adapter
 * injects:
 * <pre>
 *   DUP
 *   INVOKESTATIC Runtime.rethrowIfTimeout(Throwable)V
 * </pre>
 *
 * <p>The check-and-rethrow logic is delegated to a static method to keep the
 * injected bytecode minimal (no branches, no new basic blocks).
 *
 * <p><strong>Visitor ordering:</strong> This adapter must sit <em>between</em>
 * the {@link org.objectweb.asm.commons.JSRInlinerAdapter} and the
 * {@link org.objectweb.asm.ClassWriter}, <em>not</em> above the
 * JSRInlinerAdapter.  When placed above, the injected code is buffered inside
 * the JSRInlinerAdapter's {@link org.objectweb.asm.tree.MethodNode}, which
 * doubles memory pressure for classes that also go through the testability
 * transformation (ClassNode → JSRInlinerAdapter pipeline).  Placing it below
 * ensures the injected code is written directly to the ClassWriter output.
 *
 * <p><strong>IMPORTANT:</strong> This adapter deliberately extends plain
 * {@link MethodVisitor} (not {@link org.objectweb.asm.commons.AdviceAdapter}
 * or {@code AbstractEvoMethodAdapter}) to avoid re-entrancy issues with
 * constructors and the super-call tracking machinery in AdviceAdapter.
 */
public class TimeoutExceededRethrowAdapter extends MethodVisitor {

    private Set<Label> catchHandlerLabels;

    /**
     * Some compilers protect the first instruction of a catch or finally handler with the
     * handler itself (exception table entry {@code [handler, handler+1) -> handler}). Code
     * injected at the handler label would sit inside that range, and an exception it throws
     * would dispatch straight back to the same handler, forever. This holds for finally
     * handlers too, although nothing is injected there: the kill-switch probe at the start
     * of the handler's line throws the same way, and a thread caught in that loop never
     * ends (104_vuze's EventWaiter.waitForEvent). Such ranges are moved to start at a fresh
     * label emitted after the handler's first code.
     */
    private Map<Label, Label> shiftedRangeStarts;

    /**
     * javac also emits a handler range that begins earlier and runs past the handler's first
     * instruction: a finally block after a catch block is protected as
     * {@code [catch handler, finally handler + 1) -> finally handler} (104_vuze's
     * AESemaphore.reserve). The handler is then inside a range that leads back to it, so
     * the kill-switch probe at its start throws into the handler again. The range cannot be
     * split here, because the exception table is written before the code is seen; the
     * probe is left out instead: it is the first thing of the handler's line, and the
     * handler's code, or what follows it, carries probes of its own.
     */
    private final java.util.List<Label[]> tryCatchRanges = new java.util.ArrayList<>();
    private final Set<Label> visitedLabels = new HashSet<>();
    private boolean dropNextProbe = false;

    private boolean injected = false;

    private static final String RUNTIME_INTERNAL = "org/evosuite/runtime/Runtime";

    public TimeoutExceededRethrowAdapter(MethodVisitor mv) {
        super(Opcodes.ASM9, mv);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        boolean rethrowAtHandler = isTimeoutCatchType(type);
        tryCatchRanges.add(new Label[] {start, end, handler});
        if (start == handler) {
            if (shiftedRangeStarts == null) {
                shiftedRangeStarts = new HashMap<>();
            }
            start = shiftedRangeStarts.computeIfAbsent(handler, h -> new Label());
        }
        super.visitTryCatchBlock(start, end, handler, type);
        if (rethrowAtHandler) {
            if (catchHandlerLabels == null) {
                catchHandlerLabels = new HashSet<>();
            }
            catchHandlerLabels.add(handler);
        }
    }

    @Override
    public void visitLabel(Label label) {
        flushShiftedStart();
        super.visitLabel(label);
        if (isCoveredByOwnRange(label)) {
            dropNextProbe = true;
        }
        visitedLabels.add(label);
        boolean injectsHere = catchHandlerLabels != null && catchHandlerLabels.remove(label);
        if (injectsHere) {
            // At handler entry the caught exception is on top of the stack.
            // Inject: DUP; Runtime.rethrowIfTimeout(ex);
            // The method checks the class name and rethrows if it is TimeoutExceeded.
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL,
                    "rethrowIfTimeout", "(Ljava/lang/Throwable;)V", false);
            injected = true;
        }
        if (shiftedRangeStarts != null) {
            Label shiftedStart = shiftedRangeStarts.remove(label);
            if (shiftedStart != null) {
                if (injectsHere) {
                    super.visitLabel(shiftedStart);
                } else {
                    // Nothing is injected here, so the kill-switch probe that follows the
                    // handler's line number is the first thing to run in the handler. It
                    // must stay outside the self-covering range: when it throws, the
                    // exception would be caught by the very handler it leaves, which
                    // runs the probe again, for as long as the kill switch is on.
                    pendingShiftedStart = shiftedStart;
                }
            }
        }
    }

    private Label pendingShiftedStart;

    /** Whether a range that leads to {@code handler} has started and not yet ended here. */
    private boolean isCoveredByOwnRange(Label handler) {
        for (Label[] range : tryCatchRanges) {
            if (range[2] == handler && range[0] != handler && range[1] != handler && visitedLabels.contains(range[0])
                    && !visitedLabels.contains(range[1])) {
                return true;
            }
        }
        return false;
    }

    private void flushShiftedStart() {
        if (pendingShiftedStart != null) {
            Label l = pendingShiftedStart;
            pendingShiftedStart = null;
            super.visitLabel(l);
        }
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                boolean isInterface) {
        if (dropNextProbe && isProbe(opcode, owner, name, descriptor)) {
            dropNextProbe = false;
            return;
        }
        dropNextProbe = false;
        if (pendingShiftedStart != null && isProbe(opcode, owner, name, descriptor)) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            flushShiftedStart();
            return;
        }
        flushShiftedStart();
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    private static boolean isProbe(int opcode, String owner, String name, String descriptor) {
        // The client's probe, and the runtime's when the class is instrumented for a standalone replay.
        return opcode == Opcodes.INVOKESTATIC && "()V".equals(descriptor)
                && (("checkTimeout".equals(name) && owner.endsWith("/ExecutionTracer"))
                || ("killIfTimeout".equals(name) && owner.endsWith("/KillSwitchHandler")));
    }

    @Override
    public void visitInsn(int opcode) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitInsn(opcode);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitVarInsn(opcode, varIndex);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitLdcInsn(Object value) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitLdcInsn(value);
    }

    @Override
    public void visitIincInsn(int varIndex, int increment) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitIincInsn(varIndex, increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                       Object... bootstrapMethodArguments) {
        dropNextProbe = false;
        flushShiftedStart();
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle,
                bootstrapMethodArguments);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // DUP adds one extra stack slot.  With COMPUTE_FRAMES this value is
        // ignored, but we bump it for COMPUTE_MAXS compatibility.
        super.visitMaxs(injected ? maxStack + 1 : maxStack, maxLocals);
    }

    private static boolean isTimeoutCatchType(String type) {
        return "java/lang/Throwable".equals(type)
                || "java/lang/Exception".equals(type)
                || "java/lang/RuntimeException".equals(type);
    }
}
