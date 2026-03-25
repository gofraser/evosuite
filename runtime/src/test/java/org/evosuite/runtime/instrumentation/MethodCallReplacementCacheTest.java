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
package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.RuntimeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;

public class MethodCallReplacementCacheTest {

    @BeforeEach
    public void enableJvmMocking() {
        RuntimeSettings.mockJVMNonDeterminism = true;
    }

    @AfterEach
    public void resetState() {
        RuntimeSettings.mockJVMNonDeterminism = false;
        MethodCallReplacementCache.resetSingleton();
    }

    @Test
    public void testStaticReplacementRequiresMethodInMockClass() throws Exception {
        Method console = java.lang.System.class.getMethod("console");
        String consoleKey = console.getName() + Type.getMethodDescriptor(console);

        Method arraycopy = java.lang.System.class.getMethod(
                "arraycopy",
                Object.class,
                int.class,
                Object.class,
                int.class,
                int.class);
        String arraycopyKey = arraycopy.getName() + Type.getMethodDescriptor(arraycopy);

        MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();
        Assertions.assertTrue(cache.hasReplacementCall("java/lang/System", consoleKey));
        Assertions.assertFalse(cache.hasReplacementCall("java/lang/System", arraycopyKey));
    }

    @Test
    public void testHeadlessSwingReplacementsAreRegistered() throws Exception {
        Method jListDrag = javax.swing.JList.class.getMethod("setDragEnabled", boolean.class);
        String jListKey = jListDrag.getName() + Type.getMethodDescriptor(jListDrag);

        Method jTreeDrag = javax.swing.JTree.class.getMethod("setDragEnabled", boolean.class);
        String jTreeKey = jTreeDrag.getName() + Type.getMethodDescriptor(jTreeDrag);

        Method jTableDrag = javax.swing.JTable.class.getMethod("setDragEnabled", boolean.class);
        String jTableKey = jTableDrag.getName() + Type.getMethodDescriptor(jTableDrag);

        Method setDropTarget = javax.swing.JComponent.class.getMethod(
                "setDropTarget", java.awt.dnd.DropTarget.class);
        String dropTargetKey = setDropTarget.getName() + Type.getMethodDescriptor(setDropTarget);

        Method setMixingCutoutShape = java.awt.Component.class.getMethod(
                "setMixingCutoutShape", java.awt.Shape.class);
        String mixingCutoutKey = setMixingCutoutShape.getName() + Type.getMethodDescriptor(setMixingCutoutShape);

        MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JList", jListKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTree", jTreeKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTable", jTableKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JComponent", dropTargetKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Component", mixingCutoutKey));
    }
}
