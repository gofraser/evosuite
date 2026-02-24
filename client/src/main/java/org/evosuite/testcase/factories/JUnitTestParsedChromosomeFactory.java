/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.testcase.factories;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
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
 * ChromosomeFactory that seeds the search with test cases parsed from JUnit source code.
 * This is the source-based counterpart of {@link JUnitTestCarvedChromosomeFactory},
 * which uses bytecode carving instead.
 *
 * <p>Source-based parsing is simpler and faster than bytecode carving (no runtime execution,
 * no instrumentation). It enables seeding from LLM-generated tests or human-written tests
 * without needing them to compile or execute.
 */
public class JUnitTestParsedChromosomeFactory implements
        ChromosomeFactory<TestChromosome> {

    private static final long serialVersionUID = 1L;

    private static final Logger logger = LoggerFactory.getLogger(JUnitTestParsedChromosomeFactory.class);

    private final List<TestCase> parsedTests = new ArrayList<>();

    private final ChromosomeFactory<TestChromosome> defaultFactory;

    /**
     * The parsed test cases are used only with a certain probability P. So,
     * with probability 1-P the 'default' factory is rather used.
     *
     * @param defaultFactory the default factory.
     * @throws IllegalStateException if Properties are not properly set
     */
    public JUnitTestParsedChromosomeFactory(
            ChromosomeFactory<TestChromosome> defaultFactory)
            throws IllegalStateException {
        this.defaultFactory = defaultFactory;
        readTestCases();
    }

    /**
     * Package-private constructor for testing. Accepts pre-parsed test cases directly.
     */
    JUnitTestParsedChromosomeFactory(
            ChromosomeFactory<TestChromosome> defaultFactory,
            List<TestCase> testCases) {
        this.defaultFactory = defaultFactory;
        this.parsedTests.addAll(testCases);
    }

    private void readTestCases() throws IllegalStateException {
        if (Properties.SELECTED_JUNIT == null || Properties.SELECTED_JUNIT.isEmpty()) {
            LoggingUtils.getEvoLogger().warn("* No SELECTED_JUNIT classes specified for PARSED_JUNIT seeding");
            return;
        }

        String[] classNames = Properties.SELECTED_JUNIT.split(":");
        TestParser parser = TestParser.forSUT();

        for (String className : classNames) {
            String sourcePath = className.replace('.', File.separatorChar) + ".java";
            String sourceCode = locateAndReadSource(sourcePath);

            if (sourceCode == null) {
                LoggingUtils.getEvoLogger().warn("* Could not find source for {}", className);
                continue;
            }

            try {
                List<ParseResult> results = parser.parseTestClass(sourceCode);
                for (ParseResult result : results) {
                    TestCase tc = result.getTestCase();
                    if (tc != null && tc.size() > 0) {
                        parsedTests.add(tc);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Parsed test method '{}': {} statements",
                                    result.getOriginalMethodName(), tc.size());
                        }
                    } else {
                        logger.info("Skipping empty parsed test '{}' from {}",
                                result.getOriginalMethodName(), className);
                    }
                    if (!result.getDiagnostics().isEmpty()) {
                        logger.info("Parse diagnostics for {}.{}: {}",
                                className, result.getOriginalMethodName(),
                                result.getDiagnostics());
                    }
                }
            } catch (Exception e) {
                LoggingUtils.getEvoLogger().warn("* Error parsing test class {}: {}",
                        className, e.getMessage());
                logger.debug("Parse error details", e);
            }
        }

        // Filter to tests that reference the target class
        if (Properties.TARGET_CLASS != null && !parsedTests.isEmpty()) {
            int beforeFilter = parsedTests.size();
            parsedTests.removeIf(tc -> !referencesTargetClass(tc, Properties.TARGET_CLASS));
            if (parsedTests.size() < beforeFilter) {
                logger.info("Filtered parsed tests from {} to {} (only keeping tests that reference {})",
                        beforeFilter, parsedTests.size(), Properties.TARGET_CLASS);
            }
        }

        if (!parsedTests.isEmpty()) {
            LoggingUtils.getEvoLogger().info("* Using {} parsed tests from JUnit source for seeding",
                    parsedTests.size());
            if (logger.isDebugEnabled()) {
                for (TestCase test : parsedTests) {
                    logger.debug("Parsed Test: {}", test.toCode());
                }
            }
        }
    }

    /**
     * Locate and read the source file for the given relative path.
     * Searches SEED_TEST_SOURCE_DIR first, then common source directories.
     */
    private String locateAndReadSource(String relativePath) {
        List<String> searchDirs = new ArrayList<>();

        if (Properties.SEED_TEST_SOURCE_DIR != null) {
            searchDirs.add(Properties.SEED_TEST_SOURCE_DIR);
        }

        // Common Maven/Gradle test source locations
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
     * Checks if there are any parsed test cases available.
     */
    public boolean hasParsedTestCases() {
        return !parsedTests.isEmpty();
    }

    /**
     * Returns the number of parsed test cases.
     */
    public int getNumParsedTestCases() {
        return parsedTests.size();
    }

    /**
     * Returns the list of parsed test cases.
     */
    public List<TestCase> getParsedTestCases() {
        return parsedTests;
    }

    /**
     * Check if a parsed test case references the target class via any
     * constructor or method call.
     */
    private static boolean referencesTargetClass(TestCase tc, String targetClassName) {
        for (int i = 0; i < tc.size(); i++) {
            Statement stmt = tc.getStatement(i);
            if (stmt instanceof ConstructorStatement) {
                String declClass = ((ConstructorStatement) stmt).getConstructor()
                        .getDeclaringClass().getCanonicalName();
                if (targetClassName.equals(declClass)) return true;
            } else if (stmt instanceof MethodStatement) {
                String declClass = ((MethodStatement) stmt).getMethod()
                        .getDeclaringClass().getCanonicalName();
                if (targetClassName.equals(declClass)) return true;
            }
        }
        return false;
    }

    @Override
    public TestChromosome getChromosome() {
        final int N_mutations = Properties.SEED_MUTATIONS;
        final double P_clone = Properties.SEED_CLONE;

        double r = Randomness.nextDouble();

        if (r >= P_clone || parsedTests.isEmpty()) {
            logger.debug("Using random test");
            return defaultFactory.getChromosome();
        }

        // Cloning
        logger.info("Cloning parsed test");
        TestCase test = Randomness.choice(parsedTests);
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test.clone());
        if (N_mutations > 0) {
            int numMutations = Randomness.nextInt(N_mutations);
            logger.debug("Mutations: " + numMutations);

            for (int i = 0; i < numMutations; i++) {
                chromosome.mutate();
            }
        }

        return chromosome;
    }
}
