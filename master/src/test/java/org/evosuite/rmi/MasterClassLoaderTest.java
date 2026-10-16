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
package org.evosuite.rmi;

import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The master resolves project classes during RMI deserialisation. Libraries that EvoSuite
 * bundles itself (jackson-databind, gson) must still come from the project classpath,
 * otherwise "Unknown constructor" fails the suite transfer for every SUT that uses them.
 */
public class MasterClassLoaderTest {

    private static final String SHADOWED = "com.google.gson.Gson";

    @TempDir
    Path projectDir;

    @BeforeEach
    void setUp() throws Exception {
        reset();
        // A stand-in for the project's own copy of a class that the master jar also carries.
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SHADOWED.replace('.', '/'), null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "PROJECT_COPY", "I", null, null).visitEnd();
        cw.visitEnd();
        Path classFile = projectDir.resolve(SHADOWED.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, cw.toByteArray());
        ClassPathHandler.getInstance().changeTargetClassPath(new String[]{projectDir.toString()});
    }

    @AfterEach
    void tearDown() throws Exception {
        ClassPathHandler.getInstance().changeTargetClassPath(new String[]{
                new File(MasterClassLoaderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath()});
        reset();
    }

    @Test
    void projectCopyWinsOverBundledLibrary() throws Exception {
        Class<?> bundled = Class.forName(SHADOWED);
        Class<?> resolved = MasterClassLoader.get().loadClass(SHADOWED);
        assertNotSame(bundled, resolved, "project class must not be shadowed by EvoSuite's bundled copy");
        assertSame(MasterClassLoader.get(), resolved.getClassLoader());
        assertNotNull(resolved.getDeclaredField("PROJECT_COPY"));
    }

    @Test
    void evosuiteAndJdkClassesKeepTheirIdentity() throws Exception {
        assertSame(Properties.class, MasterClassLoader.get().loadClass(Properties.class.getName()));
        assertSame(String.class, MasterClassLoader.get().loadClass("java.lang.String"));
    }

    private static void reset() {
        try {
            Field instance = MasterClassLoader.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
