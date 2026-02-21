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
package org.evosuite.instrumentation.error;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.mockito.Mockito.*;

public class TestErrorInstrumentation {

    private ErrorConditionMethodAdapter mv;
    private NullPointerExceptionInstrumentation npeInstrumentation;

    @BeforeEach
    public void setUp() {
        mv = mock(ErrorConditionMethodAdapter.class);
        npeInstrumentation = new NullPointerExceptionInstrumentation(mv);
        // Default behavior for newLocal to return some index
        when(mv.newLocal(any(Type.class))).thenReturn(1);
        when(mv.getMethodName()).thenReturn("testMethod");
    }

    @Test
    public void testInvokeVirtualAddsNullCheck() {
        String owner = "java/lang/String";
        String name = "length";
        String desc = "()I";

        npeInstrumentation.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, desc, false);

        // Verify that we duplicated the callee (on stack)
        // Since length() takes no args, getMethodCallee just duplicates.
        verify(mv).dup();

        // Verify branch insertion (IFNONNULL)
        verify(mv).tagBranch();
        verify(mv).visitJumpInsn(eq(Opcodes.IFNONNULL), any());
    }

    @Test
    public void testInvokeSpecialAddsNullCheck() {
        String owner = "java/util/ArrayList";
        String name = "privateMethod";
        String desc = "()V";

        npeInstrumentation.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, name, desc, false);

        verify(mv).dup();
        verify(mv).tagBranch();
        verify(mv).visitJumpInsn(eq(Opcodes.IFNONNULL), any());
    }

    @Test
    public void testInvokeSpecialInitDoesNotAddNullCheck() {
        String owner = "java/util/ArrayList";
        String name = "<init>";
        String desc = "()V";

        npeInstrumentation.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, name, desc, false);

        // Should not duplicate or tag branch
        verify(mv, never()).dup();
        verify(mv, never()).tagBranch();
    }
}
