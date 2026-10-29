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
package org.evosuite.testcarver.wrapper;

import org.evosuite.testcarver.capture.Capturer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The carver wrappers record the name and descriptor of the JDK method they stand in for as string
 * constants. The code generator resolves the recorded call by reflection, so a typo silently drops
 * the call. This test checks every recorded name and descriptor against the wrapped JDK class.
 */
public class WrapperSignatureAuditTest {

    private static final String[] WRAPPERS = {
            "org.evosuite.testcarver.wrapper.java.util.Date",
            "org.evosuite.testcarver.wrapper.java.util.Calendar",
            "org.evosuite.testcarver.wrapper.java.text.DateFormat",
            "org.evosuite.testcarver.wrapper.java.text.SimpleDateFormat",
    };

    private static boolean exists(Class<?> jdkClass, String name, String desc) {
        for (Class<?> c = jdkClass; c != null; c = c.getSuperclass()) {
            if (name.equals("<init>")) {
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    if (Type.getConstructorDescriptor(k).equals(desc)) {
                        return true;
                    }
                }
            } else {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<String[]> recordedSignatures(Class<?> wrapper) throws Exception {
        final List<String[]> result = new ArrayList<>();
        final String resource = wrapper.getName().replace('.', '/') + ".class";
        final ClassNode node = new ClassNode();
        try (InputStream in = wrapper.getClassLoader().getResourceAsStream(resource)) {
            new ClassReader(in).accept(node, 0);
        }
        final String capturer = Type.getInternalName(Capturer.class);
        for (MethodNode method : node.methods) {
            final List<String> strings = new ArrayList<>();
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof String) {
                    strings.add((String) ((LdcInsnNode) insn).cst);
                } else if (insn instanceof MethodInsnNode) {
                    final MethodInsnNode call = (MethodInsnNode) insn;
                    if (call.owner.equals(capturer) && call.name.equals("capture")) {
                        Assertions.assertTrue(strings.size() >= 2,
                                wrapper.getName() + "." + method.name + " records no name and descriptor constants");
                        result.add(new String[]{method.name,
                                strings.get(strings.size() - 2), strings.get(strings.size() - 1),
                                (method.access & Opcodes.ACC_STATIC) == 0 ? method.desc : null});
                    }
                }
            }
        }
        return result;
    }

    @Test
    public void testRecordedSignaturesExistOnWrappedClass() throws Exception {
        final List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String wrapperName : WRAPPERS) {
            final Class<?> wrapper = Class.forName(wrapperName);
            final Class<?> jdkClass = wrapper.getSuperclass();
            for (String[] sig : recordedSignatures(wrapper)) {
                checked++;
                if (!exists(jdkClass, sig[1], sig[2])) {
                    problems.add(wrapper.getSimpleName() + "." + sig[0] + " records " + sig[1] + sig[2]
                            + " which does not exist on " + jdkClass.getName());
                }
                // a wrapper that shadows a method forwards the arguments of that very method
                if (sig[3] != null && sig[0].equals(sig[1]) && !Arrays.equals(Type.getArgumentTypes(sig[2]),
                        Type.getArgumentTypes(sig[3]))) {
                    problems.add(wrapper.getSimpleName() + "." + sig[0] + sig[3] + " records the arguments of "
                            + sig[1] + sig[2]);
                }
            }
        }
        Assertions.assertTrue(checked > 100, "audit found only " + checked + " recorded signatures");
        Assertions.assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
