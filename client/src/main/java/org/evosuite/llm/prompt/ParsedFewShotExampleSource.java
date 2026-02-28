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
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Thread-safe, lazily-initialized source of parsed external JUnit tests
 * for FEW_SHOT prompting.
 *
 * <p>Reads from the same {@code SELECTED_JUNIT} / {@code SEED_TEST_SOURCE_DIR}
 * configuration used by {@code JUnitTestParsedChromosomeFactory}, but without
 * requiring that factory to be instantiated. The result is cached for the
 * lifetime of the JVM (or until {@link #reset()} is called, e.g. for testing).
 */
public final class ParsedFewShotExampleSource {

    private static final Logger logger =
            LoggerFactory.getLogger(ParsedFewShotExampleSource.class);

    private static volatile ParsedFewShotExampleSource instance;

    /**
     * Package-private loader indirection for testing. When non-null, used
     * instead of {@link #loadParsedTests()} during reload. Always null in
     * production. Does not affect thread-safety guarantees.
     */
    static volatile Supplier<List<TestCase>> loaderOverride;

    private final List<TestCase> parsedTests;
    /** Property tuple key used when this instance was created. */
    private final String propertyKey;

    private ParsedFewShotExampleSource(List<TestCase> parsedTests, String propertyKey) {
        this.parsedTests = Collections.unmodifiableList(parsedTests);
        this.propertyKey = propertyKey;
    }

    /**
     * Returns the singleton instance, lazily loading parsed tests on first call.
     * Automatically invalidates and reloads when the property tuple
     * (TARGET_CLASS, SELECTED_JUNIT, SEED_TEST_SOURCE_DIR) changes.
     * Thread-safe via double-checked locking.
     */
    public static ParsedFewShotExampleSource getInstance() {
        String currentKey = currentPropertyKey();
        ParsedFewShotExampleSource local = instance;
        if (local == null || !currentKey.equals(local.propertyKey)) {
            synchronized (ParsedFewShotExampleSource.class) {
                local = instance;
                if (local == null || !currentKey.equals(local.propertyKey)) {
                    Supplier<List<TestCase>> loader = loaderOverride;
                    List<TestCase> tests = loader != null ? loader.get() : loadParsedTests();
                    local = new ParsedFewShotExampleSource(tests, currentKey);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Builds a composite key from the mutable properties that affect loading. */
    static String currentPropertyKey() {
        return Properties.TARGET_CLASS + "|" + Properties.SELECTED_JUNIT
                + "|" + Properties.SEED_TEST_SOURCE_DIR;
    }

    /** Returns the cached parsed test cases (never null). */
    public List<TestCase> getParsedTests() {
        return parsedTests;
    }

    /** Returns the property key this instance was created with. Package-private for testing. */
    String getPropertyKey() {
        return propertyKey;
    }

    /** Resets the singleton, forcing re-load on next access. For testing only. */
    public static synchronized void reset() {
        instance = null;
    }

    /**
     * Injects an explicit instance (for testing). Replaces the singleton.
     */
    public static synchronized void setInstance(ParsedFewShotExampleSource src) {
        instance = src;
    }

    /**
     * Creates a test-only instance wrapping the given tests.
     */
    public static ParsedFewShotExampleSource ofTests(List<TestCase> tests) {
        return new ParsedFewShotExampleSource(
                tests != null ? new ArrayList<>(tests) : Collections.emptyList(),
                currentPropertyKey());
    }

    // ---- loading logic (mirrors JUnitTestParsedChromosomeFactory) ----

    private static List<TestCase> loadParsedTests() {
        if (Properties.SELECTED_JUNIT == null || Properties.SELECTED_JUNIT.isEmpty()) {
            logger.debug("No SELECTED_JUNIT configured; parsed FEW_SHOT source empty");
            return Collections.emptyList();
        }

        List<TestCase> result = new ArrayList<>();
        String[] classNames = Properties.SELECTED_JUNIT.split(":");
        TestParser parser;
        try {
            parser = TestParser.forSUT();
        } catch (Exception e) {
            logger.debug("TestParser.forSUT() unavailable for FEW_SHOT parsed source", e);
            return Collections.emptyList();
        }

        for (String className : classNames) {
            String relativePath = className.replace('.', File.separatorChar) + ".java";
            String sourceCode = locateAndReadSource(relativePath);
            if (sourceCode == null) {
                logger.debug("Could not find source for FEW_SHOT parsed class {}", className);
                continue;
            }
            try {
                List<ParseResult> parseResults = parser.parseTestClass(sourceCode);
                for (ParseResult pr : parseResults) {
                    TestCase tc = pr.getTestCase();
                    if (tc != null && tc.size() > 0) {
                        result.add(tc);
                    }
                }
            } catch (Exception e) {
                logger.debug("Error parsing {} for FEW_SHOT: {}", className, e.getMessage());
            }
        }

        logger.info("ParsedFewShotExampleSource: loaded {} parsed tests from SELECTED_JUNIT",
                result.size());

        // Filter to tests referencing TARGET_CLASS (mirrors JUnitTestParsedChromosomeFactory)
        String targetClass = Properties.TARGET_CLASS;
        if (targetClass != null && !targetClass.isEmpty()) {
            int beforeFilter = result.size();
            result.removeIf(tc -> {
                try {
                    return !referencesTargetClass(tc, targetClass);
                } catch (Exception e) {
                    logger.debug("Error checking target class reference, skipping test: {}",
                            e.getMessage());
                    return true; // skip problematic tests safely
                }
            });
            logger.debug("ParsedFewShotExampleSource: filtered {} → {} tests for TARGET_CLASS={}",
                    beforeFilter, result.size(), targetClass);
        }

        return result;
    }

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
                    logger.debug("Could not read {}: {}", path, e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * Check if a parsed test case references the target class via any
     * constructor or method call. Mirrors
     * {@code JUnitTestParsedChromosomeFactory.referencesTargetClass}.
     */
    static boolean referencesTargetClass(TestCase tc, String targetClassName) {
        for (int i = 0; i < tc.size(); i++) {
            Statement stmt = tc.getStatement(i);
            if (stmt instanceof ConstructorStatement) {
                String declClass = ((ConstructorStatement) stmt).getConstructor()
                        .getDeclaringClass().getCanonicalName();
                if (targetClassName.equals(declClass)) {
                    return true;
                }
            } else if (stmt instanceof MethodStatement) {
                String declClass = ((MethodStatement) stmt).getMethod()
                        .getDeclaringClass().getCanonicalName();
                if (targetClassName.equals(declClass)) {
                    return true;
                }
            }
        }
        return false;
    }
}
