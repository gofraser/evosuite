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
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testparser.ParsedJUnitSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static List<TestCase> loadParsedTests() {
        if (Properties.SELECTED_JUNIT == null || Properties.SELECTED_JUNIT.isEmpty()) {
            logger.debug("No SELECTED_JUNIT configured; parsed FEW_SHOT source empty");
            return Collections.emptyList();
        }

        ParsedJUnitSources.Loaded loaded;
        try {
            loaded = ParsedJUnitSources.load(Properties.SELECTED_JUNIT);
        } catch (Exception e) {
            logger.debug("TestParser.forSUT() unavailable for FEW_SHOT parsed source", e);
            return Collections.emptyList();
        }
        for (String className : loaded.getMissingSources()) {
            logger.debug("Could not find source for FEW_SHOT parsed class {}", className);
        }
        for (String error : loaded.getParseErrors()) {
            logger.debug("{} (FEW_SHOT)", error);
        }

        List<TestCase> result = new ArrayList<>(loaded.getTests());
        logger.info("ParsedFewShotExampleSource: loaded {} parsed tests from SELECTED_JUNIT",
                result.size());

        // Filter to tests referencing TARGET_CLASS, as JUnitTestParsedChromosomeFactory does
        String targetClass = Properties.TARGET_CLASS;
        if (targetClass != null && !targetClass.isEmpty()) {
            int beforeFilter = result.size();
            ParsedJUnitSources.retainReferencingTarget(result, targetClass);
            logger.debug("ParsedFewShotExampleSource: filtered {} → {} tests for TARGET_CLASS={}",
                    beforeFilter, result.size(), targetClass);
        }

        return result;
    }
}
