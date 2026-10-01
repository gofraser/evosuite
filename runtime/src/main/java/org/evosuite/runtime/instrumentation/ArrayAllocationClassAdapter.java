/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.runtime.instrumentation;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Adds cumulative array-allocation accounting to methods in a class.
 *
 * <p>The client-side adapter extends {@link ArrayAllocationMethodAdapter} to
 * add the client-only per-allocation and factory guards. Keeping the
 * cumulative instrumentation here lets the runtime agent apply the same
 * accounting during replay without depending on the client module.</p>
 */
public class ArrayAllocationClassAdapter extends ClassVisitor {

    private String className;

    public ArrayAllocationClassAdapter(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

        // Match the replay loop-counter scope: synthetic/bridge methods and static
        // initializers are outside the per-test method execution budget.
        if ((access & Opcodes.ACC_SYNTHETIC) > 0
                || (access & Opcodes.ACC_BRIDGE) > 0
                || "<clinit>".equals(name)) {
            return mv;
        }

        return new ArrayAllocationMethodAdapter(mv, className, access, name, desc);
    }
}
