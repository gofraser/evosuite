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

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Applies {@link TimeoutExceededRethrowAdapter} to every method of a class under test.
 *
 * <p>Non-target classes get it through {@link NonTargetClassAdapter}, but a loop that
 * swallows {@code Throwable} inside the class under test itself used to keep a SUT thread
 * alive past the kill switch: {@code TimeoutExceeded} thrown by a probe in a callee was
 * caught and the loop went round again (vuze's {@code TRTrackerServerImpl} timer loop
 * survived from test to test this way, holding a monitor the next test thread needed).
 * Like in the non-target chain, this must sit right above the {@code ClassWriter}, below
 * the adapters that buffer the method as a {@code MethodNode}.</p>
 */
public class TimeoutExceededRethrowClassAdapter extends ClassVisitor {

    public TimeoutExceededRethrowClassAdapter(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                     String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
        return new TimeoutExceededRethrowAdapter(mv);
    }
}
