/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.instrumentation;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;

/**
 * A minimal {@link MethodVisitor} that prevents SUT catch blocks from
 * swallowing EvoSuite's {@code TestCaseExecutor.TimeoutExceeded} exception.
 *
 * <p>At every catch/finally handler entry point whose declared type could
 * catch {@code TimeoutExceeded} (i.e. {@code Throwable}, {@code Exception},
 * {@code RuntimeException}, or {@code null} for finally), this adapter
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

    private boolean injected = false;

    private static final String RUNTIME_INTERNAL = "org/evosuite/runtime/Runtime";

    public TimeoutExceededRethrowAdapter(MethodVisitor mv) {
        super(Opcodes.ASM9, mv);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        super.visitTryCatchBlock(start, end, handler, type);
        if (type == null
                || "java/lang/Throwable".equals(type)
                || "java/lang/Exception".equals(type)
                || "java/lang/RuntimeException".equals(type)) {
            if (catchHandlerLabels == null) {
                catchHandlerLabels = new HashSet<>();
            }
            catchHandlerLabels.add(handler);
        }
    }

    @Override
    public void visitLabel(Label label) {
        super.visitLabel(label);
        if (catchHandlerLabels != null && catchHandlerLabels.remove(label)) {
            // At handler entry the caught exception is on top of the stack.
            // Inject: DUP; Runtime.rethrowIfTimeout(ex);
            // The method checks the class name and rethrows if it is TimeoutExceeded.
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL,
                    "rethrowIfTimeout", "(Ljava/lang/Throwable;)V", false);
            injected = true;
        }
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // DUP adds one extra stack slot.  With COMPUTE_FRAMES this value is
        // ignored, but we bump it for COMPUTE_MAXS compatibility.
        super.visitMaxs(injected ? maxStack + 1 : maxStack, maxLocals);
    }
}
