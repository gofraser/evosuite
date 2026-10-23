/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
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
        new QueueInstrumentation(mv).visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Queue",
                "remove", "()Ljava/lang/Object;", true);

        verify(mv).visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Queue", "isEmpty", "()Z", true);
    }

    @Test
    public void queueClassGuardUsesInvokeVirtual() {
        new QueueInstrumentation(mv).visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayDeque",
                "remove", "()Ljava/lang/Object;", false);

        verify(mv).visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayDeque", "isEmpty", "()Z", false);
    }

    @Test
    public void dequeInterfaceGuardUsesInvokeInterface() {
        new DequeInstrumentation(mv).visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Deque",
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
