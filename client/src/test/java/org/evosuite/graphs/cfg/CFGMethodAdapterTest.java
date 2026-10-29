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
package org.evosuite.graphs.cfg;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.BranchPool;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CFGMethodAdapterTest {

    @Test
    public void testClear() {
        ClassLoader cl = new ClassLoader() {};
        BranchPool.getInstance(cl).getMethodSet("Class1").add("method1");

        assertTrue(BranchPool.getInstance(cl).getNumMethods() > 0, "Should have methods before clear");

        BranchPool.clearAll();

        assertEquals(0, BranchPool.getInstance(cl).getNumMethods(), "Should have 0 methods after clear");
    }

    public static class Sample {
        public int twice(int x) {
            return x * 2;
        }
    }

    @Test
    public void testMethodIsInstrumentedWhenItsLoaderIsReleasedMidway() throws Exception {
        // A class loaded from another thread (e.g. the Swing event queue of the SUT) can still
        // be under instrumentation when the generation releases the loader's entry.
        ClassLoader cl = CFGMethodAdapterTest.class.getClassLoader();
        String target = Sample.class.getName();
        String oldTarget = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = target;
        try {
            ClassReader reader = new ClassReader(target);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    MethodVisitor adapter = new CFGMethodAdapter(cl, target, access, name, desc,
                            signature, exceptions, new MethodVisitor(Opcodes.ASM9) {});
                    BranchPool.release(cl);
                    return adapter;
                }
            }, 0);
            assertTrue(BranchPool.getInstance(cl).getNumMethods() > 0);
        } finally {
            Properties.TARGET_CLASS = oldTarget;
            BranchPool.clearAll();
        }
    }
}
