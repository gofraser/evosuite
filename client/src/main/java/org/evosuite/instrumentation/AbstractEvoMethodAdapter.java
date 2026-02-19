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

package org.evosuite.instrumentation;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Base class for EvoSuite method adapters that handles common concerns like
 * skipping static initializers and tracking the super constructor call in
 * initializers.
 */
public abstract class AbstractEvoMethodAdapter extends AdviceAdapter {

    protected final String className;
    protected final String methodName;
    protected final String descriptor;
    protected final boolean isConstructor;
    protected final boolean isStaticInitializer;

    /**
     * If true, this adapter will not perform any instrumentation in {@code <clinit>}.
     */
    protected boolean skipStaticInitializer = true;

    /**
     * Tracks whether the super() or this() call has been seen in a constructor.
     * For non-constructors, this is always true.
     */
    protected boolean isSuperCallDone = false;

    protected AbstractEvoMethodAdapter(MethodVisitor mv, int access, String className,
                                       String methodName, String descriptor) {
        super(Opcodes.ASM9, mv, access, methodName, descriptor);
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.isConstructor = "<init>".equals(methodName);
        this.isStaticInitializer = "<clinit>".equals(methodName);
        if (!isConstructor) {
            isSuperCallDone = true;
        }
    }

    @Override
    protected void onMethodEnter() {
        isSuperCallDone = true;
        super.onMethodEnter();
    }

    /**
     * Subclasses should use this instead of isStaticInitializer if they want
     * to respect the skipStaticInitializer flag.
     */
    protected boolean shouldSkip() {
        return skipStaticInitializer && isStaticInitializer;
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(Math.max(getExtraStackSlots(), maxStack), maxLocals);
    }

    /**
     * Returns the number of extra stack slots required by the instrumentation.
     */
    protected abstract int getExtraStackSlots();
}
