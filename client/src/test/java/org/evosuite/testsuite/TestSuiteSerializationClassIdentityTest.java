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
package org.evosuite.testsuite;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * A SUT may ship a library that EvoSuite bundles as well, in a different version.
 * Serialized tests must be read back against the SUT's class, not EvoSuite's copy.
 */
public class TestSuiteSerializationClassIdentityTest {

    /** A class that is on EvoSuite's own class path. */
    private static final String COLLIDING = "org.apache.commons.lang3.mutable.MutableInt";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void deserializationBindsToTheSutClassNotEvoSuitesCopy() throws Exception {
        // The SUT's version of the class: same name, different serialVersionUID and shape.
        Path dir = folder.getRoot().toPath();
        Path src = dir.resolve("src/org/apache/commons/lang3/mutable/MutableInt.java");
        Files.createDirectories(src.getParent());
        Files.write(src, ("package org.apache.commons.lang3.mutable;\n"
                + "public class MutableInt implements java.io.Serializable {\n"
                + "  private static final long serialVersionUID = 7L;\n"
                + "  public String label = \"sut\";\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "-d", classes.toString(), src.toString()));

        try (URLClassLoader sut = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) {
            Class<?> sutClass = sut.loadClass(COLLIDING);
            assertNotSame(Class.forName(COLLIDING), sutClass);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(sutClass.getDeclaredConstructor().newInstance());
            }

            try (ObjectInputStream in = TestSuiteSerialization.createObjectInputStream(
                    new ByteArrayInputStream(bytes.toByteArray()), sut)) {
                Object read = in.readObject();
                assertSame(sutClass, read.getClass());
                assertEquals("sut", sutClass.getField("label").get(read));
            }
        }
    }
}
