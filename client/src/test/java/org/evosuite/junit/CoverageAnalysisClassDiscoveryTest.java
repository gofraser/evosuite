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
package org.evosuite.junit;

import org.evosuite.Properties;
import org.evosuite.junit.examples.JUnit4Test;
import org.evosuite.junit.examples.Not_A_Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Class discovery of the coverage analysis for a directory and for a jar of compiled tests.
 */
public class CoverageAnalysisClassDiscoveryTest {

    private static final String TEST_ENTRY = "org/evosuite/junit/examples/JUnit4Test.class";
    private static final String NON_TEST_ENTRY = "org/evosuite/junit/examples/Not_A_Test.class";

    private String previousJunit;

    @BeforeEach
    public void rememberJunit() {
        previousJunit = Properties.JUNIT;
    }

    @AfterEach
    public void restoreJunit() {
        Properties.JUNIT = previousJunit;
    }

    private static byte[] classBytes(String entry) throws IOException {
        try (InputStream in = CoverageAnalysisClassDiscoveryTest.class.getClassLoader().getResourceAsStream(entry)) {
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Class<?>> discover(Path location) throws Exception {
        Properties.JUNIT = location.toString();
        Method method = CoverageAnalysis.class.getDeclaredMethod("getTestClasses");
        method.setAccessible(true);
        return (List<Class<?>>) method.invoke(null);
    }

    @Test
    public void directoryYieldsOnlyTestClassesAndSkipsUnreadableFiles(@TempDir Path dir) throws Exception {
        Path nested = Files.createDirectories(dir.resolve("nested"));
        Files.write(dir.resolve("JUnit4Test.class"), classBytes(TEST_ENTRY));
        Files.write(nested.resolve("Not_A_Test.class"), classBytes(NON_TEST_ENTRY));
        Files.write(nested.resolve("Broken.class"), "not a class".getBytes(StandardCharsets.UTF_8));

        assertEquals(Collections.singletonList(JUnit4Test.class.getName()), names(discover(dir)));
    }

    @Test
    public void singleClassFileIsAnalysed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("JUnit4Test.class");
        Files.write(file, classBytes(TEST_ENTRY));

        assertEquals(Collections.singletonList(JUnit4Test.class.getName()), names(discover(file)));
    }

    @Test
    public void jarYieldsOnlyTestClassesAndSkipsUnloadableEntries(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("tests.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            addEntry(zip, "README.txt", "ignored".getBytes(StandardCharsets.UTF_8));
            addEntry(zip, TEST_ENTRY, classBytes(TEST_ENTRY));
            addEntry(zip, NON_TEST_ENTRY, classBytes(NON_TEST_ENTRY));
            addEntry(zip, "org/evosuite/junit/examples/NoSuchTest.class", "not a class".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(Collections.singletonList(JUnit4Test.class.getName()), names(discover(jar)));
    }

    private static void addEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static List<String> names(List<Class<?>> classes) {
        List<String> names = new java.util.ArrayList<>();
        for (Class<?> clazz : classes) {
            names.add(clazz.getName());
        }
        return names;
    }
}
