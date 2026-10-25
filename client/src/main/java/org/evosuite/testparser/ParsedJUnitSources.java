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
package org.evosuite.testparser;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the {@code SELECTED_JUNIT} test classes from source and parses them into test cases.
 * The callers (the parsed-JUnit chromosome factory and the few-shot example source) decide
 * how loudly to report what could not be loaded.
 */
public final class ParsedJUnitSources {

    private static final Logger logger = LoggerFactory.getLogger(ParsedJUnitSources.class);

    private ParsedJUnitSources() {
    }

    /** The parsed tests and what the loader could not use. */
    public static final class Loaded {
        private final List<TestCase> tests = new ArrayList<>();
        private final List<String> missingSources = new ArrayList<>();
        private final List<String> parseErrors = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private int emptyTests;

        /** The non-empty parsed tests, in the order of the selected classes. */
        public List<TestCase> getTests() {
            return tests;
        }

        /** The selected classes whose source file was not found. */
        public List<String> getMissingSources() {
            return missingSources;
        }

        /** One message per selected class that failed to parse. */
        public List<String> getParseErrors() {
            return parseErrors;
        }

        /** Informational messages about skipped tests and parse diagnostics. */
        public List<String> getNotes() {
            return notes;
        }

        /** The number of parsed test methods that had no statements. */
        public int getEmptyTests() {
            return emptyTests;
        }
    }

    /**
     * Parses the colon-separated {@code selectedJunit} classes, which are searched for in
     * {@code SEED_TEST_SOURCE_DIR} and the usual test source directories.
     *
     * @param selectedJunit the colon-separated class names, must not be empty
     */
    public static Loaded load(String selectedJunit) {
        Loaded loaded = new Loaded();
        TestParser parser = TestParser.forSUT();

        for (String className : selectedJunit.split(":")) {
            String sourceCode = locateAndReadSource(className.replace('.', File.separatorChar) + ".java");
            if (sourceCode == null) {
                loaded.missingSources.add(className);
                continue;
            }

            try {
                for (ParseResult result : parser.parseTestClass(sourceCode)) {
                    TestCase tc = result.getTestCase();
                    if (tc != null && tc.size() > 0) {
                        loaded.tests.add(tc);
                        logger.debug("Parsed test method '{}': {} statements",
                                result.getOriginalMethodName(), tc.size());
                    } else {
                        loaded.emptyTests++;
                        loaded.notes.add("Skipping empty parsed test '" + result.getOriginalMethodName()
                                + "' from " + className);
                    }
                    if (!result.getDiagnostics().isEmpty()) {
                        loaded.notes.add("Parse diagnostics for " + className + "."
                                + result.getOriginalMethodName() + ": " + result.getDiagnostics());
                    }
                }
            } catch (Exception e) {
                loaded.parseErrors.add("Error parsing test class " + className + ": " + e.getMessage());
                logger.debug("Parse error details", e);
            }
        }
        return loaded;
    }

    /**
     * Searches SEED_TEST_SOURCE_DIR first, then the common Maven/Gradle test source directories.
     */
    private static String locateAndReadSource(String relativePath) {
        List<String> searchDirs = new ArrayList<>();
        if (Properties.SEED_TEST_SOURCE_DIR != null) {
            searchDirs.add(Properties.SEED_TEST_SOURCE_DIR);
        }
        searchDirs.add("src/test/java");
        searchDirs.add("src/test");
        searchDirs.add("test");

        for (String dir : searchDirs) {
            Path path = Paths.get(dir, relativePath);
            if (Files.isRegularFile(path)) {
                try {
                    return new String(Files.readAllBytes(path));
                } catch (IOException e) {
                    logger.warn("Could not read source file {}: {}", path, e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * Drops the tests that neither construct the target class nor call one of its methods.
     * A test that cannot be inspected is dropped too.
     *
     * @param targetClass the binary name of the class under test
     */
    public static void retainReferencingTarget(List<TestCase> tests, String targetClass) {
        tests.removeIf(tc -> {
            try {
                return !referencesTargetClass(tc, targetClass);
            } catch (Exception e) {
                logger.debug("Error checking target class reference, skipping test: {}", e.getMessage());
                return true;
            }
        });
    }

    /**
     * Check if a parsed test case references the target class via any
     * constructor or method call.
     */
    public static boolean referencesTargetClass(TestCase tc, String targetClassName) {
        for (int i = 0; i < tc.size(); i++) {
            Statement stmt = tc.getStatement(i);
            if (stmt instanceof ConstructorStatement) {
                Class<?> declClass = ((ConstructorStatement) stmt).getConstructor().getDeclaringClass();
                if (isTargetClass(declClass, targetClassName)) {
                    return true;
                }
            } else if (stmt instanceof MethodStatement) {
                Class<?> declClass = ((MethodStatement) stmt).getMethod().getDeclaringClass();
                if (isTargetClass(declClass, targetClassName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * TARGET_CLASS is a binary name ({@code a.Outer$Inner}), so a nested class under test only
     * matches by {@link Class#getName()}; the canonical name is still accepted as before.
     */
    private static boolean isTargetClass(Class<?> declClass, String targetClassName) {
        return targetClassName.equals(declClass.getName())
                || targetClassName.equals(declClass.getCanonicalName());
    }
}
