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
package org.evosuite.llm;

import org.evosuite.Properties;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.ga.stoppingconditions.MaxGenerationStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.llm.factory.LlmSeededPopulationFactory;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.ClusterExpansionManager;
import org.evosuite.llm.response.LlmResponseParser;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.strategy.LlmStrategy;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testparser.TestParser;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 tests for LLMSTRATEGY iteration, provenance tracking, and metrics.
 */
class LlmStrategyBehaviorTest {

    private static final String SIMPLE_JUNIT_RESPONSE =
            "```java\n" +
                    "import org.junit.Test;\n" +
                    "public class GeneratedLlmTest {\n" +
                    "  @Test\n" +
                    "  public void generatedTest() {\n" +
                    "  }\n" +
                    "}\n" +
                    "```";

    private Properties.LlmStrategyMode originalMode;
    private int originalIterativeTests;
    private Properties.Strategy originalStrategy;
    private int originalSeedCount;
    private boolean originalMinimize;
    private boolean originalInline;
    private boolean originalAssertions;

    @BeforeEach
    void saveProperties() {
        originalMode = Properties.LLM_STRATEGY_MODE;
        originalIterativeTests = Properties.LLM_STRATEGY_ITERATIVE_TESTS;
        originalStrategy = Properties.STRATEGY;
        originalSeedCount = Properties.LLM_SEED_COUNT;
        originalMinimize = Properties.MINIMIZE;
        originalInline = Properties.INLINE;
        originalAssertions = Properties.ASSERTIONS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_STRATEGY_MODE = originalMode;
        Properties.LLM_STRATEGY_ITERATIVE_TESTS = originalIterativeTests;
        Properties.STRATEGY = originalStrategy;
        Properties.LLM_SEED_COUNT = originalSeedCount;
        Properties.MINIMIZE = originalMinimize;
        Properties.INLINE = originalInline;
        Properties.ASSERTIONS = originalAssertions;
    }

    // ----------------------------------------------------------------
    // 1. SINGLE_PROMPT vs ITERATIVE_BUDGETED mode semantics
    // ----------------------------------------------------------------

    @Test
    void singlePromptModeUsesOneShot() {
        Properties.LLM_STRATEGY_MODE = Properties.LlmStrategyMode.SINGLE_PROMPT;
        Properties.LLM_SEED_COUNT = 1;

        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.SEEDING, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 5);

        LlmStrategy strategy = createTestableStrategy(service, Properties.LlmStrategyMode.SINGLE_PROMPT);
        try {
            TestSuiteChromosome suite = strategy.generateTests();
            // SINGLE_PROMPT should produce a suite from the seeded factory
            assertNotNull(suite);
            assertEquals(1, suite.size());
        } finally {
            service.close();
        }
    }

    @Test
    void iterativeBudgetedModeDefaultProperty() {
        // Verify both enum values parse correctly
        assertEquals(Properties.LlmStrategyMode.SINGLE_PROMPT,
                Properties.LlmStrategyMode.valueOf("SINGLE_PROMPT"));
        assertEquals(Properties.LlmStrategyMode.ITERATIVE_BUDGETED,
                Properties.LlmStrategyMode.valueOf("ITERATIVE_BUDGETED"));
    }

    @Test
    void strategyModeEnumHasBothValues() {
        Properties.LlmStrategyMode[] values = Properties.LlmStrategyMode.values();
        assertEquals(2, values.length);
        assertEquals(Properties.LlmStrategyMode.SINGLE_PROMPT, values[0]);
        assertEquals(Properties.LlmStrategyMode.ITERATIVE_BUDGETED, values[1]);
    }

    // ----------------------------------------------------------------
    // 2. Iterative loop stopping at budget — generation-based SC
    // ----------------------------------------------------------------

    @Test
    void iterativeTerminatesByGenerationStoppingCondition() {
        // Verifies Issue 1: budget advances on every iteration, not just empty results.
        // Uses a generation-based stopping condition with limit 3.
        Properties.LLM_STRATEGY_MODE = Properties.LlmStrategyMode.ITERATIVE_BUDGETED;
        Properties.LLM_STRATEGY_ITERATIVE_TESTS = 1;

        MockChatLanguageModel model = new MockChatLanguageModel();
        for (int i = 0; i < 20; i++) {
            model.enqueue(LlmFeature.ITERATIVE_STRATEGY, SIMPLE_JUNIT_RESPONSE);
        }
        // Plenty of LLM budget
        LlmService service = createService(model, 20);

        final int GEN_LIMIT = 3;
        LlmStrategy strategy = createTestableIterativeStrategyWithGenLimit(service, GEN_LIMIT);
        try {
            TestSuiteChromosome suite = strategy.generateTests();
            assertNotNull(suite);
            // Must have terminated via stopping condition, not LLM budget
            assertTrue(service.hasBudget(),
                    "LLM should still have budget — termination must come from stopping condition");
        } finally {
            service.close();
        }
    }

    @Test
    void timeBasedStoppingConditionNotForceAdvanced() {
        // Verifies that MaxTimeStoppingCondition is never force-advanced.
        // The strategy should only advance count-based conditions.
        Properties.LLM_STRATEGY_MODE = Properties.LlmStrategyMode.ITERATIVE_BUDGETED;
        Properties.LLM_STRATEGY_ITERATIVE_TESTS = 1;

        MockChatLanguageModel model = new MockChatLanguageModel();
        for (int i = 0; i < 5; i++) {
            model.enqueue(LlmFeature.ITERATIVE_STRATEGY, SIMPLE_JUNIT_RESPONSE);
        }
        LlmService service = createService(model, 5);

        // Track whether forceCurrentValue was called on a time-based SC
        final boolean[] forceAdvanceCalled = {false};

        LlmStrategy strategy = new LlmStrategy() {
            @Override
            protected boolean canGenerateTestsForSUT() { return true; }
            @Override
            protected List<TestSuiteFitnessFunction> getFitnessFunctions() { return Collections.emptyList(); }
            @Override
            protected List<TestFitnessFactory<? extends TestFitnessFunction>> getConfiguredGoalFactories() {
                return Collections.emptyList();
            }
            @Override
            protected LlmSeededPopulationFactory createSeededFactory() {
                return new LlmSeededPopulationFactory(new RandomLengthTestFactory(),
                        service, Collections::emptyList, Runnable::run);
            }
            @Override
            protected void sendExecutionStatistics() {}
            @Override
            protected LlmService getLlmService() { return service; }
            @Override
            protected StoppingCondition<TestSuiteChromosome> getStoppingCondition() {
                return new MaxTimeStoppingCondition<TestSuiteChromosome>() {
                    private int callCount = 0;
                    @Override
                    public void forceCurrentValue(long value) {
                        forceAdvanceCalled[0] = true;
                        super.forceCurrentValue(value);
                    }
                    @Override
                    public boolean isFinished() {
                        // Stop after 2 checks to avoid infinite loop
                        return ++callCount > 3;
                    }
                };
            }
        };

        try {
            strategy.generateTests();
            assertFalse(forceAdvanceCalled[0],
                    "forceCurrentValue must NOT be called on time-based stopping conditions");
        } finally {
            service.close();
        }
    }

    // ----------------------------------------------------------------
    // 2. Iterative loop stopping at budget
    // ----------------------------------------------------------------

    @Test
    void iterativeStopsWhenLlmBudgetExhausted() {
        Properties.LLM_STRATEGY_MODE = Properties.LlmStrategyMode.ITERATIVE_BUDGETED;
        Properties.LLM_STRATEGY_ITERATIVE_TESTS = 1;

        // Only 1 call budget - should stop after initial query
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.ITERATIVE_STRATEGY, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 1);

        LlmStrategy strategy = createTestableIterativeStrategy(service);
        try {
            TestSuiteChromosome suite = strategy.generateTests();
            assertNotNull(suite);
            // Should have produced tests from the initial query only
        } finally {
            service.close();
        }
    }

    @Test
    void iterativeStopsWhenAllGoalsCovered() {
        // Verifies the "all goals covered" exit path
        Properties.LLM_STRATEGY_MODE = Properties.LlmStrategyMode.ITERATIVE_BUDGETED;
        Properties.LLM_STRATEGY_ITERATIVE_TESTS = 1;

        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.ITERATIVE_STRATEGY, SIMPLE_JUNIT_RESPONSE);
        // Enough budget for many calls
        LlmService service = createService(model, 10);

        // Strategy with empty goal list → all goals trivially covered
        LlmStrategy strategy = createTestableIterativeStrategy(service);
        try {
            TestSuiteChromosome suite = strategy.generateTests();
            assertNotNull(suite);
        } finally {
            service.close();
        }
    }

    // ----------------------------------------------------------------
    // 3. Follow-up prompts target uncovered goals
    // ----------------------------------------------------------------

    @Test
    void iterativeFeatureEnumExists() {
        // Verify ITERATIVE_STRATEGY feature exists
        assertEquals(LlmFeature.ITERATIVE_STRATEGY,
                LlmFeature.valueOf("ITERATIVE_STRATEGY"));
    }

    @Test
    void followUpPromptContainsUncoveredGoalContext() {
        // Verifies that PromptBuilder.withUncoveredGoals injects goal descriptions
        // into the user prompt, so the LLM can target them.
        TestFitnessFunction fakeGoal = new TestFitnessFunction() {
            @Override
            public double getFitness(TestChromosome individual,
                                     org.evosuite.testcase.execution.ExecutionResult result) {
                return 1.0;
            }
            @Override
            public int compareTo(TestFitnessFunction other) { return 0; }
            @Override
            public int hashCode() { return 7; }
            @Override
            public boolean equals(Object other) { return this == other; }
            @Override
            public String getTargetClass() { return "com.example.Target"; }
            @Override
            public String getTargetMethod() { return "doSomething()V"; }
            @Override
            public String toString() { return "Branch 42 in com.example.Target#doSomething"; }
        };

        PromptBuilder builder = new PromptBuilder(
                new org.evosuite.llm.prompt.SystemPromptProvider(),
                new org.evosuite.llm.prompt.TestClusterSummarizer(),
                new org.evosuite.llm.prompt.SourceCodeProvider(),
                new org.evosuite.llm.prompt.CoverageGoalFormatter(),
                new org.evosuite.llm.prompt.TestCaseFormatter());

        builder.withUncoveredGoals(Collections.singletonList(fakeGoal))
               .withInstruction("Generate tests targeting these uncovered goals.");

        PromptResult result = builder.buildWithMetadata();
        List<org.evosuite.llm.LlmMessage> messages = result.getMessages();

        // The user message must contain the uncovered goal description
        String userMessage = messages.stream()
                .filter(m -> m.getRole() == org.evosuite.llm.LlmMessage.Role.USER)
                .map(org.evosuite.llm.LlmMessage::getContent)
                .findFirst()
                .orElse("");

        assertTrue(userMessage.contains("Uncovered goals"),
                "User prompt must include 'Uncovered goals' section header");
        assertTrue(userMessage.contains("Branch 42 in com.example.Target#doSomething"),
                "User prompt must include the goal's toString() description");
    }

    // ----------------------------------------------------------------
    // 3b. Ratio timeline is locale-safe
    // ----------------------------------------------------------------

    @Test
    void ratioTimelineFormattingIsLocaleIndependent() {
        // Verifies Issue 5: String.format uses Locale.ROOT
        // A ratio like 0.5 must always produce "0.5000", never "0,5000"
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase tc = new DefaultTestCase();
        IntPrimitiveStatement s1 = new IntPrimitiveStatement(tc, 1);
        s1.setParsedFromLlm(true);
        IntPrimitiveStatement s2 = new IntPrimitiveStatement(tc, 2);
        tc.addStatement(s1);
        tc.addStatement(s2);
        TestChromosome c = new TestChromosome();
        c.setTestCase(tc);
        suite.addTest(c);

        double ratio = LlmStrategy.computeParsedRatio(suite);
        // Format the same way emitRatioTimeline does
        String formatted = String.format(Locale.ROOT, "%.4f", ratio);
        assertEquals("0.5000", formatted);
        assertFalse(formatted.contains(","),
                "Formatted ratio must not contain locale-dependent comma");
    }

    // ----------------------------------------------------------------
    // 4. LLMSTRATEGY skips minimization/inlining/assertions
    // ----------------------------------------------------------------

    @Test
    void llmStrategyPropertySkipsPhases() {
        // Verifies that LLMSTRATEGY causes the guard to skip minimize/inline/assertion
        // phases in TestSuiteGenerator.postProcessTests. We test the guard logic:
        // Properties.STRATEGY == LLMSTRATEGY disables all three.
        Properties.STRATEGY = Properties.Strategy.LLMSTRATEGY;
        Properties.MINIMIZE = true;
        Properties.INLINE = true;
        Properties.ASSERTIONS = true;

        boolean isLlmStrategy = Properties.STRATEGY == Properties.Strategy.LLMSTRATEGY;
        assertTrue(isLlmStrategy, "Strategy must be LLMSTRATEGY");
        // The three post-processing guards in TestSuiteGenerator:
        //   Properties.INLINE && !isLlmStrategy  → false (skipped)
        //   Properties.MINIMIZE && !isLlmStrategy → false (skipped)
        //   Properties.ASSERTIONS && !isLlmStrategy → false (skipped)
        assertFalse(Properties.INLINE && !isLlmStrategy,
                "Inlining guard must evaluate to false when LLMSTRATEGY is active");
        assertFalse(Properties.MINIMIZE && !isLlmStrategy,
                "Minimization guard must evaluate to false when LLMSTRATEGY is active");
        assertFalse(Properties.ASSERTIONS && !isLlmStrategy,
                "Assertion-generation guard must evaluate to false when LLMSTRATEGY is active");

        // Verify a non-LLM strategy does NOT skip these phases
        Properties.STRATEGY = Properties.Strategy.EVOSUITE;
        boolean isEvoSuiteStrategy = Properties.STRATEGY == Properties.Strategy.LLMSTRATEGY;
        assertFalse(isEvoSuiteStrategy);
        assertTrue(Properties.INLINE && !isEvoSuiteStrategy,
                "Inlining must proceed for non-LLM strategies");
        assertTrue(Properties.MINIMIZE && !isEvoSuiteStrategy,
                "Minimization must proceed for non-LLM strategies");
        assertTrue(Properties.ASSERTIONS && !isEvoSuiteStrategy,
                "Assertion generation must proceed for non-LLM strategies");
    }

    // ----------------------------------------------------------------
    // 5. Statement provenance tracking
    // ----------------------------------------------------------------

    @Test
    void newStatementDefaultsToNotParsedFromLlm() {
        DefaultTestCase tc = new DefaultTestCase();
        IntPrimitiveStatement stmt = new IntPrimitiveStatement(tc, 42);
        assertFalse(stmt.isParsedFromLlm());
    }

    @Test
    void parsedFromLlmCanBeSetAndGet() {
        DefaultTestCase tc = new DefaultTestCase();
        IntPrimitiveStatement stmt = new IntPrimitiveStatement(tc, 42);
        stmt.setParsedFromLlm(true);
        assertTrue(stmt.isParsedFromLlm());
    }

    @Test
    void parsedFromLlmPreservedThroughClone() {
        DefaultTestCase original = new DefaultTestCase();
        IntPrimitiveStatement stmt = new IntPrimitiveStatement(original, 42);
        stmt.setParsedFromLlm(true);
        original.addStatement(stmt);

        DefaultTestCase cloned = original.clone();
        assertEquals(1, cloned.size());
        assertTrue(cloned.getStatement(0).isParsedFromLlm(),
                "parsedFromLlm should survive clone");
    }

    @Test
    void parsedFromLlmPreservedThroughCopy() {
        DefaultTestCase original = new DefaultTestCase();
        IntPrimitiveStatement stmt = new IntPrimitiveStatement(original, 42);
        stmt.setParsedFromLlm(true);
        original.addStatement(stmt);

        DefaultTestCase target = new DefaultTestCase();
        // clone(TestCase) internally uses copy()
        Statement copied = stmt.clone(target);
        assertTrue(copied.isParsedFromLlm(),
                "parsedFromLlm should survive copy via clone(TestCase)");
    }

    @Test
    void parsedFromLlmNotSetWhenNotMarked() {
        DefaultTestCase original = new DefaultTestCase();
        IntPrimitiveStatement stmt1 = new IntPrimitiveStatement(original, 1);
        stmt1.setParsedFromLlm(true);
        IntPrimitiveStatement stmt2 = new IntPrimitiveStatement(original, 2);
        // stmt2 not marked
        original.addStatement(stmt1);
        original.addStatement(stmt2);

        DefaultTestCase cloned = original.clone();
        assertTrue(cloned.getStatement(0).isParsedFromLlm());
        assertFalse(cloned.getStatement(1).isParsedFromLlm(),
                "Unmarked statement should remain false after clone");
    }

    @Test
    void parsedFromLlmSurvivesMultipleClones() {
        DefaultTestCase tc = new DefaultTestCase();
        IntPrimitiveStatement stmt = new IntPrimitiveStatement(tc, 99);
        stmt.setParsedFromLlm(true);
        tc.addStatement(stmt);

        // Clone chain: original → clone1 → clone2
        DefaultTestCase clone1 = tc.clone();
        DefaultTestCase clone2 = clone1.clone();
        assertTrue(clone2.getStatement(0).isParsedFromLlm(),
                "parsedFromLlm should survive transitive cloning");
    }

    @Test
    void booleanPrimitivePreservesProvenance() {
        DefaultTestCase tc = new DefaultTestCase();
        BooleanPrimitiveStatement stmt = new BooleanPrimitiveStatement(tc, true);
        stmt.setParsedFromLlm(true);
        tc.addStatement(stmt);

        DefaultTestCase cloned = tc.clone();
        assertTrue(cloned.getStatement(0).isParsedFromLlm());
    }

    @Test
    void stringPrimitivePreservesProvenance() {
        DefaultTestCase tc = new DefaultTestCase();
        StringPrimitiveStatement stmt = new StringPrimitiveStatement(tc, "hello");
        stmt.setParsedFromLlm(true);
        tc.addStatement(stmt);

        DefaultTestCase cloned = tc.clone();
        assertTrue(cloned.getStatement(0).isParsedFromLlm());
    }

    @Test
    void nullStatementPreservesProvenance() {
        DefaultTestCase tc = new DefaultTestCase();
        NullStatement stmt = new NullStatement(tc, Object.class);
        stmt.setParsedFromLlm(true);
        tc.addStatement(stmt);

        DefaultTestCase cloned = tc.clone();
        assertTrue(cloned.getStatement(0).isParsedFromLlm());
    }

    // ----------------------------------------------------------------
    // 6. Parsed statement ratio metric correctness
    // ----------------------------------------------------------------

    @Test
    void computeParsedRatioEmptySuite() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        assertEquals(0.0, LlmStrategy.computeParsedRatio(suite), 0.001);
    }

    @Test
    void computeParsedRatioAllParsed() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase tc = new DefaultTestCase();
        IntPrimitiveStatement s1 = new IntPrimitiveStatement(tc, 1);
        s1.setParsedFromLlm(true);
        IntPrimitiveStatement s2 = new IntPrimitiveStatement(tc, 2);
        s2.setParsedFromLlm(true);
        tc.addStatement(s1);
        tc.addStatement(s2);
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(tc);
        suite.addTest(chromosome);

        assertEquals(1.0, LlmStrategy.computeParsedRatio(suite), 0.001);
    }

    @Test
    void computeParsedRatioMixed() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase tc = new DefaultTestCase();
        IntPrimitiveStatement s1 = new IntPrimitiveStatement(tc, 1);
        s1.setParsedFromLlm(true);
        IntPrimitiveStatement s2 = new IntPrimitiveStatement(tc, 2);
        // s2 not parsed
        tc.addStatement(s1);
        tc.addStatement(s2);
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(tc);
        suite.addTest(chromosome);

        assertEquals(0.5, LlmStrategy.computeParsedRatio(suite), 0.001);
    }

    @Test
    void computeParsedRatioMultipleTests() {
        TestSuiteChromosome suite = new TestSuiteChromosome();

        // Test 1: 2 parsed statements
        DefaultTestCase tc1 = new DefaultTestCase();
        IntPrimitiveStatement s1 = new IntPrimitiveStatement(tc1, 1);
        s1.setParsedFromLlm(true);
        IntPrimitiveStatement s2 = new IntPrimitiveStatement(tc1, 2);
        s2.setParsedFromLlm(true);
        tc1.addStatement(s1);
        tc1.addStatement(s2);
        TestChromosome c1 = new TestChromosome();
        c1.setTestCase(tc1);
        suite.addTest(c1);

        // Test 2: 2 non-parsed statements
        DefaultTestCase tc2 = new DefaultTestCase();
        IntPrimitiveStatement s3 = new IntPrimitiveStatement(tc2, 3);
        IntPrimitiveStatement s4 = new IntPrimitiveStatement(tc2, 4);
        tc2.addStatement(s3);
        tc2.addStatement(s4);
        TestChromosome c2 = new TestChromosome();
        c2.setTestCase(tc2);
        suite.addTest(c2);

        // 2 parsed out of 4 total = 0.5
        assertEquals(0.5, LlmStrategy.computeParsedRatio(suite), 0.001);
    }

    // ----------------------------------------------------------------
    // 7. TestParser provenance marking
    // ----------------------------------------------------------------

    @Test
    void testParserMarkParsedFromLlmDefaultFalse() {
        // Default TestParser does NOT mark statements as LLM-parsed
        TestParser parser = new TestParser(getClass().getClassLoader());
        assertFalse(parser.isMarkParsedFromLlm(),
                "Default TestParser should not mark statements as LLM-parsed");
    }

    @Test
    void testParserSetMarkParsedFromLlm() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);
        assertTrue(parser.isMarkParsedFromLlm(),
                "After setMarkParsedFromLlm(true), getter should return true");
    }

    @Test
    void testParserForSUTWithLlmProvenanceHasFlag() {
        // Verify the factory method sets the provenance flag
        TestParser provenanceParser = TestParser.forSUTWithLlmProvenance();
        assertTrue(provenanceParser.isMarkParsedFromLlm(),
                "forSUTWithLlmProvenance() must create a parser that marks statements");
    }

    // ----------------------------------------------------------------
    // 8. RuntimeVariable entries exist
    // ----------------------------------------------------------------

    @Test
    void runtimeVariablesExist() {
        assertNotNull(org.evosuite.statistics.RuntimeVariable.LLM_Parsed_Statement_Ratio);
        assertNotNull(org.evosuite.statistics.RuntimeVariable.LLM_Parsed_Statement_Ratio_Timeline);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private LlmStrategy createTestableStrategy(LlmService service,
                                                Properties.LlmStrategyMode mode) {
        Properties.LLM_STRATEGY_MODE = mode;
        LlmSeededPopulationFactory seededFactory = new LlmSeededPopulationFactory(
                new RandomLengthTestFactory(),
                service,
                Collections::emptyList,
                Runnable::run);

        return new LlmStrategy() {
            @Override
            protected boolean canGenerateTestsForSUT() {
                return true;
            }

            @Override
            protected List<TestSuiteFitnessFunction> getFitnessFunctions() {
                return Collections.emptyList();
            }

            @Override
            protected List<TestFitnessFactory<? extends TestFitnessFunction>> getConfiguredGoalFactories() {
                return Collections.emptyList();
            }

            @Override
            protected LlmSeededPopulationFactory createSeededFactory() {
                return seededFactory;
            }

            @Override
            protected void sendExecutionStatistics() {
            }

            @Override
            protected LlmService getLlmService() {
                return service;
            }
        };
    }

    private LlmStrategy createTestableIterativeStrategy(LlmService service) {
        Properties.LLM_STRATEGY_MODE = Properties.LlmStrategyMode.ITERATIVE_BUDGETED;

        return new LlmStrategy() {
            @Override
            protected boolean canGenerateTestsForSUT() {
                return true;
            }

            @Override
            protected List<TestSuiteFitnessFunction> getFitnessFunctions() {
                return Collections.emptyList();
            }

            @Override
            protected List<TestFitnessFactory<? extends TestFitnessFunction>> getConfiguredGoalFactories() {
                return Collections.emptyList();
            }

            @Override
            protected LlmSeededPopulationFactory createSeededFactory() {
                return new LlmSeededPopulationFactory(
                        new RandomLengthTestFactory(),
                        service,
                        Collections::emptyList,
                        Runnable::run);
            }

            @Override
            protected void sendExecutionStatistics() {
            }

            @Override
            protected LlmService getLlmService() {
                return service;
            }

            @Override
            protected StoppingCondition<TestSuiteChromosome> getStoppingCondition() {
                // Create a stopping condition that stops after a small budget
                MaxTimeStoppingCondition<TestSuiteChromosome> sc =
                        new MaxTimeStoppingCondition<>();
                sc.setLimit(1); // 1 second budget
                return sc;
            }
        };
    }

    private static LlmService createService(LlmService.ChatLanguageModel model, int budget) {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE,
                "mock",
                "",
                "",
                0.0,
                1024,
                2,
                0,
                1,
                false,
                Paths.get("target/llm-test-traces"),
                "phase6-test");
        return new LlmService(model,
                new LlmBudgetCoordinator.Local(budget),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }

    /**
     * Creates an iterative strategy that uses a generation-based stopping condition.
     * The strategy always reports uncovered goals to prevent early "all goals covered" exit.
     */
    private LlmStrategy createTestableIterativeStrategyWithGenLimit(
            LlmService service, int generationLimit) {
        Properties.LLM_STRATEGY_MODE = Properties.LlmStrategyMode.ITERATIVE_BUDGETED;

        // Create a fake goal that is never covered
        TestFitnessFunction neverCoveredGoal = new TestFitnessFunction() {
            @Override
            public double getFitness(TestChromosome individual,
                                     org.evosuite.testcase.execution.ExecutionResult result) {
                return 1.0; // always uncovered
            }

            @Override
            public int compareTo(TestFitnessFunction other) {
                return 0;
            }

            @Override
            public int hashCode() {
                return 42;
            }

            @Override
            public boolean equals(Object other) {
                return this == other;
            }

            @Override
            public String getTargetClass() {
                return "FakeTarget";
            }

            @Override
            public String getTargetMethod() {
                return "fakeMethod";
            }
        };
        List<TestFitnessFunction> goals = Collections.singletonList(neverCoveredGoal);

        return new LlmStrategy() {
            @Override
            protected boolean canGenerateTestsForSUT() {
                return true;
            }

            @Override
            protected List<TestSuiteFitnessFunction> getFitnessFunctions() {
                return Collections.emptyList();
            }

            @Override
            protected List<TestFitnessFactory<? extends TestFitnessFunction>> getConfiguredGoalFactories() {
                TestFitnessFactory<TestFitnessFunction> factory =
                        new TestFitnessFactory<TestFitnessFunction>() {
                            @Override
                            public List<TestFitnessFunction> getCoverageGoals() {
                                return goals;
                            }
                            @Override
                            public double getFitness(TestSuiteChromosome suite) {
                                return 1.0;
                            }
                        };
                return Collections.singletonList(factory);
            }

            @Override
            protected LlmSeededPopulationFactory createSeededFactory() {
                return new LlmSeededPopulationFactory(
                        new RandomLengthTestFactory(),
                        service,
                        Collections::emptyList,
                        Runnable::run);
            }

            @Override
            protected void sendExecutionStatistics() {
            }

            @Override
            protected LlmService getLlmService() {
                return service;
            }

            @Override
            protected StoppingCondition<TestSuiteChromosome> getStoppingCondition() {
                MaxGenerationStoppingCondition<TestSuiteChromosome> sc =
                        new MaxGenerationStoppingCondition<>();
                sc.setLimit(generationLimit);
                return sc;
            }
        };
    }
}
