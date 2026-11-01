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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guards inserted before calls on collection interfaces call isEmpty() and size() on the same
 * receiver. An invokevirtual on an interface owner makes the JVM throw IncompatibleClassChangeError,
 * so the guard has to use invokeinterface when the guarded call is one.
 */
public class CollectionInterfaceInstrumentationTest {

    private ErrorConditionMethodAdapter mv;

    @BeforeEach
    public void setUp() {
        mv = mock(ErrorConditionMethodAdapter.class);
        when(mv.newLocal(any(Type.class))).thenReturn(1);
        when(mv.getMethodName()).thenReturn("testMethod");
    }

    @Test
    public void queueInterfaceGuardUsesInvokeInterface() {
        EmptyCollectionInstrumentation.queue(mv).visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Queue",
                "remove", "()Ljava/lang/Object;", true);

        verify(mv).visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Queue", "isEmpty", "()Z", true);
    }

    @Test
    public void queueClassGuardUsesInvokeVirtual() {
        EmptyCollectionInstrumentation.queue(mv).visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayDeque",
                "remove", "()Ljava/lang/Object;", false);

        verify(mv).visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayDeque", "isEmpty", "()Z", false);
    }

    @Test
    public void dequeInterfaceGuardUsesInvokeInterface() {
        EmptyCollectionInstrumentation.deque(mv).visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Deque",
                "removeFirst", "()Ljava/lang/Object;", true);

        verify(mv).visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Deque", "isEmpty", "()Z", true);
    }

    @Test
    public void listInterfaceGuardUsesInvokeInterface() {
        new ListInstrumentation(mv).visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List",
                "get", "(I)Ljava/lang/Object;", true);

        verify(mv).visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true);
    }
}
