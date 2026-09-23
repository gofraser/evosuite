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
package org.evosuite.runtime;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime module is shipped to users and compiled at target 1.8, so a generated test
 * may well execute {@link ReflectionAccess} on a Java 8 JVM. Naming a type that arrived
 * with the module system would fail verification the moment the class is linked, and every
 * {@code setAccessible} the SUT makes goes through here. The Java 9 half therefore has to
 * stay in {@code ModuleAccessRules}, which lives under {@code src/main/java11} and is only
 * reached through {@link ModuleAccessPolicy}.
 */
class ReflectionAccessJava8Test {

    /** Members that do not exist before Java 9, as {@code owner#name}. */
    private static final Set<String> POST_JAVA8_MEMBERS = new LinkedHashSet<>();

    static {
        POST_JAVA8_MEMBERS.add("java/lang/reflect/AccessibleObject#trySetAccessible");
        POST_JAVA8_MEMBERS.add("java/lang/Class#getModule");
        POST_JAVA8_MEMBERS.add("java/lang/Class#getPackageName");
        POST_JAVA8_MEMBERS.add("java/lang/Runtime#version");
    }

    /** Types that do not exist before Java 9. */
    private static final Set<String> POST_JAVA8_TYPES = new LinkedHashSet<>();

    static {
        POST_JAVA8_TYPES.add("java/lang/Module");
        POST_JAVA8_TYPES.add("java/lang/ModuleLayer");
        POST_JAVA8_TYPES.add("java/lang/Runtime$Version");
        POST_JAVA8_TYPES.add("java/lang/reflect/InaccessibleObjectException");
    }

    @Test
    void theFacadeNamesNothingThatPostDatesJava8() throws IOException {
        assertEquals(emptySorted(), postJava8ReferencesOf(ReflectionAccess.class));
        assertEquals(emptySorted(), postJava8ReferencesOf(ModuleAccessPolicy.class));
    }

    /**
     * Keeps the check above honest: the rules are the half that is allowed — and required —
     * to name those types, so if nothing is found there the detector has stopped working.
     */
    @Test
    void theDetectorFindsTheReferencesTheRulesAreAllowedToHave() throws Exception {
        Class<?> rules = Class.forName("org.evosuite.runtime.ModuleAccessRules", false,
                ReflectionAccess.class.getClassLoader());
        assertTrue(postJava8ReferencesOf(rules).contains("java/lang/Module"),
                "ModuleAccessRules is expected to use the module API");
    }

    @Test
    void theFacadeIsCompiledForJava8() throws IOException {
        int[] version = new int[1];
        new ClassReader(bytecodeOf(ReflectionAccess.class))
                .accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int v, int access, String name, String sig, String sup, String[] itf) {
                        version[0] = v & 0xFFFF;
                    }
                }, ClassReader.SKIP_CODE);
        assertEquals(52, version[0], "ReflectionAccess must stay a Java 8 class file");
    }

    /**
     * The rules are reached by name, so a typo or a move would not break the build — it
     * would silently leave every {@code setAccessible} allowed, which is exactly the bug
     * the class exists to prevent.
     */
    @Test
    void theModuleRulesAreStillWhereTheFacadeLooksForThem() throws Exception {
        assertTrue(ReflectionAccess.javaSpecificationVersion() >= 9,
                "this suite is expected to run on Java 9 or later");

        Class<?> rules = Class.forName("org.evosuite.runtime.ModuleAccessRules", true,
                ReflectionAccess.class.getClassLoader());
        assertTrue(ModuleAccessPolicy.class.isAssignableFrom(rules));
        assertNotNull(rules.getDeclaredConstructor().newInstance());
    }

    private static Set<String> emptySorted() {
        return new TreeSet<>();
    }

    private static Set<String> postJava8ReferencesOf(Class<?> clazz) throws IOException {
        Set<String> found = new TreeSet<>();
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                recordTypesIn(descriptor, found);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        recordMember(owner, methodName, found);
                        recordTypesIn(methodDescriptor, found);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                                               String fieldDescriptor) {
                        recordMember(owner, fieldName, found);
                        recordTypesIn(fieldDescriptor, found);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        recordType(type, found);
                    }
                };
            }

            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                             String signature, Object value) {
                recordTypesIn(descriptor, found);
                return null;
            }
        };
        new ClassReader(bytecodeOf(clazz)).accept(visitor, 0);
        return found;
    }

    private static void recordMember(String owner, String name, Set<String> found) {
        String key = owner + "#" + name;
        if (POST_JAVA8_MEMBERS.contains(key)) {
            found.add(key);
        }
        recordType(owner, found);
    }

    private static void recordType(String internalName, Set<String> found) {
        if (POST_JAVA8_TYPES.contains(internalName)) {
            found.add(internalName);
        }
    }

    private static void recordTypesIn(String descriptor, Set<String> found) {
        int from = 0;
        while ((from = descriptor.indexOf('L', from)) >= 0) {
            int end = descriptor.indexOf(';', from);
            if (end < 0) {
                return;
            }
            recordType(descriptor.substring(from + 1, end), found);
            from = end + 1;
        }
    }

    private static byte[] bytecodeOf(Class<?> clazz) throws IOException {
        String resource = clazz.getName().replace('.', '/') + ".class";
        try (InputStream in = clazz.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "cannot read " + resource);
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
