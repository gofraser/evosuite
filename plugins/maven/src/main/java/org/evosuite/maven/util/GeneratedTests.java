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
package org.evosuite.maven.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The folder of generated tests, and what the build needs to know about the tests in it.
 */
public final class GeneratedTests {

    /**
     * Where generated tests go by default, kept apart from hand-written tests so that
     * regenerating them can never touch those.
     */
    public static final String DEFAULT_FOLDER = "src/test/evosuite";

    /**
     * Suffix of generated test classes, see the {@code junit_suffix} property.
     */
    public static final String TEST_SUFFIX = "_ESTest";

    private static final String SCAFFOLDING_SUFFIX = TEST_SUFFIX + "_scaffolding";

    private GeneratedTests() {
    }

    /**
     * What the generated tests need on the test classpath.
     */
    public static final class Requirements {
        public boolean evosuiteRuntime;
        public boolean jupiter;
        public boolean junit4;
    }

    /**
     * Resolve the folder against the module's base directory, unless it is absolute.
     *
     * @param basedir the module's base directory
     * @param folder  the configured folder
     * @return the folder
     */
    public static File resolveFolder(File basedir, String folder) {
        File file = new File(folder);
        return file.isAbsolute() ? file : new File(basedir, folder);
    }

    /**
     * Find out from their imports which libraries the generated tests use.
     *
     * @param folder the folder of generated tests, which may not exist yet
     * @return the libraries needed
     * @throws IOException if a test cannot be read
     */
    public static Requirements scanRequirements(File folder) throws IOException {
        Requirements needed = new Requirements();
        for (Path test : javaFiles(folder)) {
            for (String line : Files.readAllLines(test, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("public ") || trimmed.startsWith("class ")
                        || trimmed.startsWith("@")) {
                    break; // imports are over
                }
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                needed.evosuiteRuntime |= trimmed.contains(" org.evosuite.runtime.")
                        || trimmed.contains(" static org.evosuite.runtime.");
                needed.jupiter |= trimmed.contains("org.junit.jupiter.");
                needed.junit4 |= trimmed.startsWith("import org.junit.Test;")
                        || trimmed.startsWith("import org.junit.runner.")
                        || trimmed.startsWith("import static org.junit.Assert.");
            }
        }
        return needed;
    }

    /**
     * Add the runtime as java agent to an argument line, unless it is already there.
     *
     * @param argLine    the existing argument line, possibly {@code null}
     * @param runtimeJar the EvoSuite runtime jar
     * @return the argument line with the agent
     */
    public static String appendAgent(String argLine, File runtimeJar) {
        String path = runtimeJar.getAbsolutePath();
        String option = "-javaagent:" + path;
        if (path.contains(" ")) {
            option = "\"" + option + "\"";
        }
        if (argLine == null || argLine.trim().isEmpty()) {
            return option;
        }
        if (argLine.contains(path)) {
            return argLine;
        }
        return argLine + " " + option;
    }

    /**
     * Generated tests (and their scaffolding) whose class under test no longer exists.
     *
     * @param folder          the folder of generated tests
     * @param classesDirectory the compiled classes of the module
     * @return the files to delete; empty if there are no compiled classes to compare with
     * @throws IOException if the folder cannot be listed
     */
    public static List<Path> findStaleTests(File folder, File classesDirectory) throws IOException {
        List<Path> stale = new ArrayList<>();
        if (!hasClassFiles(classesDirectory)) {
            // without compiled classes every test would look stale
            return stale;
        }
        Path root = folder.toPath();
        for (Path test : javaFiles(folder)) {
            String relative = root.relativize(test).toString();
            String withoutExtension = relative.substring(0, relative.length() - ".java".length());
            String cut;
            if (withoutExtension.endsWith(SCAFFOLDING_SUFFIX)) {
                cut = withoutExtension.substring(0, withoutExtension.length() - SCAFFOLDING_SUFFIX.length());
            } else if (withoutExtension.endsWith(TEST_SUFFIX)) {
                cut = withoutExtension.substring(0, withoutExtension.length() - TEST_SUFFIX.length());
            } else {
                continue; // not a generated test
            }
            if (!new File(classesDirectory, cut + ".class").exists()) {
                stale.add(test);
            }
        }
        return stale;
    }

    private static boolean hasClassFiles(File directory) throws IOException {
        if (!directory.isDirectory()) {
            return false;
        }
        try (Stream<Path> files = Files.walk(directory.toPath())) {
            return files.anyMatch(p -> p.toString().endsWith(".class"));
        }
    }

    private static List<Path> javaFiles(File folder) throws IOException {
        if (!folder.isDirectory()) {
            return new ArrayList<>();
        }
        try (Stream<Path> files = Files.walk(folder.toPath())) {
            return files.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }
}
