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

import org.evosuite.PackageInfo;
import org.evosuite.runtime.RuntimeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class SwingFallbackInstrumentationTest {

    private static final String SWING_MOCK = PackageInfo.getNameWithSlash(
            org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class);

    @BeforeEach
    public void setup() {
        RuntimeSettings.mockJVMNonDeterminism = true;
        RuntimeSettings.mockGUI = true;
        MethodCallReplacementCache.resetSingleton();
    }

    @AfterEach
    public void cleanup() {
        RuntimeSettings.mockJVMNonDeterminism = false;
        RuntimeSettings.mockGUI = false;
        MethodCallReplacementCache.resetSingleton();
    }

    private ClassNode instrument(String className) throws Exception {
        byte[] original;
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(className + ".class")) {
            Assertions.assertNotNull(in, "Missing test class bytes");
            original = in.readAllBytes();
        }
        byte[] transformed = new RuntimeInstrumentation().transformBytes(
                getClass().getClassLoader(), className, new ClassReader(original), false);
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }

    private static boolean callsSwingMock(ClassNode node, String method) {
        for (MethodNode m : node.methods) {
            if (!method.equals(m.name)) {
                continue;
            }
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
                    MethodInsnNode min = (MethodInsnNode) insn;
                    if (min.getOpcode() == Opcodes.INVOKESTATIC && SWING_MOCK.equals(min.owner)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Test
    public void addOnAnApplicationClassThatIsNotAComponentIsLeftAlone() throws Exception {
        Assertions.assertFalse(callsSwingMock(instrument("com/examples/CallsAppAdder"), "call"));
    }

    @Test
    public void addOnAnApplicationSubclassOfASwingClassIsReplaced() throws Exception {
        Assertions.assertTrue(callsSwingMock(instrument("com/examples/CallsAppPanel"), "call"));
    }
}
