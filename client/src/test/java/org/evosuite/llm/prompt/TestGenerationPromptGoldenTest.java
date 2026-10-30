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
import org.evosuite.Properties.LlmPromptTechnique;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.llm.LlmBudgetCoordinator;
import org.evosuite.llm.LlmConfiguration;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmRequest;
import org.evosuite.llm.LlmResponse;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.LlmTraceRecorder;
import org.evosuite.llm.factory.LlmSeededPopulationFactory;
import org.evosuite.llm.factory.LlmTestChromosomeFactory;
import org.evosuite.llm.search.AsyncLlmTestProducer;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.llm.search.StagnationDetector;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the complete message list (roles and text) of every test-generation prompt producer,
 * with the static-context cache on and off and with and without a prompt technique, so the
 * shared system / SUT context / few-shot / technique setup can change without changing a
 * byte the model sees. Run with {@code -Dgolden.update=true} to rewrite the golden files.
 */
class TestGenerationPromptGoldenTest {

    private static final String GOLDEN_DIR = "org/evosuite/llm/prompt/golden/";
    private static final Path SOURCE_GOLDEN_DIR = Paths.get("src/test/resources", GOLDEN_DIR);

    private String originalTargetClass;
    private boolean originalCache;
    private LlmPromptTechnique originalTechnique;
    private Properties.LlmSutContextMode originalSutMode;
    private Properties.LlmStagnationPromptMode originalStagnationPrompt;
    private boolean originalIncludeTests;
    private boolean originalTestFactory;
    private double originalFactoryProbability;

    @BeforeEach
    void saveProperties() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalCache = Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT;
        originalTechnique = Properties.LLM_PROMPT_TECHNIQUE;
        originalSutMode = Properties.LLM_SUT_CONTEXT_MODE;
        originalStagnationPrompt = Properties.LLM_STAGNATION_PROMPT;
        originalIncludeTests = Properties.LLM_ASYNC_PRODUCER_INCLUDE_TESTS;
        originalTestFactory = Properties.LLM_TEST_FACTORY;
        originalFactoryProbability = Properties.LLM_TEST_FACTORY_PROBABILITY;
        Properties.TARGET_CLASS = "com.example.Foo";
        Properties.LLM_SUT_CONTEXT_MODE = Properties.LlmSutContextMode.SIGNATURE_ONLY;
        Properties.LLM_TEST_FACTORY = true;
        Properties.LLM_TEST_FACTORY_PROBABILITY = 1.0;
        Properties.LLM_ASYNC_PRODUCER_INCLUDE_TESTS = true;
        SutContextProvider stub = new SutContextProvider() {
            @Override
            public Optional<String> getContext(String className, TestCluster cluster) {
                return Optional.of("public class Foo {\n  public int bar(int x);\n}");
            }
        };
        SutContextProviderFactory.setInstanceForTesting(new SutContextProviderFactory(stub, stub, stub, stub));
        StaticPromptContext.resetForRunCompletion();
    }

    @AfterEach
    void restoreProperties() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = originalCache;
        Properties.LLM_PROMPT_TECHNIQUE = originalTechnique;
        Properties.LLM_SUT_CONTEXT_MODE = originalSutMode;
        Properties.LLM_STAGNATION_PROMPT = originalStagnationPrompt;
        Properties.LLM_ASYNC_PRODUCER_INCLUDE_TESTS = originalIncludeTests;
        Properties.LLM_TEST_FACTORY = originalTestFactory;
        Properties.LLM_TEST_FACTORY_PROBABILITY = originalFactoryProbability;
        SutContextProviderFactory.resetInstanceForTesting();
        StaticPromptContext.resetForRunCompletion();
    }

    @FunctionalInterface
    private interface PromptSource {
        List<LlmMessage> messages() throws Exception;
    }

    private void check(String name, PromptSource source) throws Exception {
        StringBuilder rendered = new StringBuilder();
        for (boolean cache : new boolean[]{true, false}) {
            for (LlmPromptTechnique technique
                    : new LlmPromptTechnique[]{LlmPromptTechnique.NONE, LlmPromptTechnique.CHAIN_OF_THOUGHT}) {
                Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = cache;
                Properties.LLM_PROMPT_TECHNIQUE = technique;
                StaticPromptContext.resetForRunCompletion();
                rendered.append("##### cache=").append(cache).append(" technique=").append(technique).append('\n');
                List<LlmMessage> messages = source.messages();
                assertNotNull(messages, name);
                for (LlmMessage message : messages) {
                    rendered.append("----- ").append(message.getRole()).append('\n')
                            .append(message.getContent()).append('\n');
                }
            }
        }
        String actual = rendered.toString();
        if (Boolean.getBoolean("golden.update")) {
            Files.createDirectories(SOURCE_GOLDEN_DIR);
            Files.write(SOURCE_GOLDEN_DIR.resolve(name + ".txt"), actual.getBytes(StandardCharsets.UTF_8));
            return;
        }
        assertEquals(readGolden(name), actual, "prompt messages changed for " + name);
    }

    private static String readGolden(String name) throws IOException {
        try (InputStream in = TestGenerationPromptGoldenTest.class.getClassLoader()
                .getResourceAsStream(GOLDEN_DIR + name + ".txt")) {
            assertNotNull(in, "missing golden file " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void seededPopulationPrompt() throws Exception {
        check("seeded-population", () -> {
            AtomicReference<List<LlmMessage>> captured = new AtomicReference<>();
            LlmService service = service((messages, feature) -> {
                captured.compareAndSet(null, new ArrayList<>(messages));
                return LlmResponse.fromText("");
            });
            try {
                ChromosomeFactory<TestChromosome> fallback = TestChromosome::new;
                LlmSeededPopulationFactory factory = new LlmSeededPopulationFactory(
                        fallback, service, () -> goals("g1", "g2"), Runnable::run);
                factory.awaitAndDrainSeeds(1000L);
            } finally {
                service.close();
            }
            return captured.get();
        });
    }

    @Test
    void testFactoryGoalListPrompt() throws Exception {
        check("test-factory-goal-list", () -> {
            LlmService service = mock(LlmService.class);
            when(service.isAvailable()).thenReturn(true);
            when(service.hasBudget(any(LlmFeature.class))).thenReturn(true);
            AtomicReference<List<LlmMessage>> captured = new AtomicReference<>();
            when(service.query(any(LlmRequest.class))).thenAnswer(invocation -> {
                LlmRequest request = invocation.getArgument(0);
                captured.compareAndSet(null, request.getContext().getMessages());
                return LlmResponse.fromText("");
            });
            ChromosomeFactory<TestChromosome> fallback = TestChromosome::new;
            new LlmTestChromosomeFactory(fallback, service, () -> goals("g1", "g2")).getChromosome();
            return captured.get();
        });
    }

    @Test
    void targetedPrompts() throws Exception {
        check("targeted", () -> {
            TestFitnessFunction goal = new StubGoal("g1");
            TestChromosome parent = chromosome(1);
            parent.setFitness(goal, 0.25);
            TestChromosome partner = chromosome(2);
            OperatorTarget mutation = OperatorTarget.builder(parent, goal).distance(0.25).priority(0.5).build();
            OperatorTarget combination = OperatorTarget.builder(parent, new StubGoal("g2"))
                    .partner(partner).build();
            List<LlmMessage> all = new ArrayList<>(
                    new TargetedPromptFactory().build(Arrays.asList(mutation, combination)).getMessages());
            all.addAll(new TargetedPromptFactory().buildGenerate(mutation).getMessages());
            return all;
        });
    }

    @Test
    void stagnationPoolPrompt() throws Exception {
        check("stagnation-pool", () -> {
            Properties.LLM_STAGNATION_PROMPT = Properties.LlmStagnationPromptMode.POOL;
            StagnationDetector detector = new StagnationDetector(null, 1, 1, new RepeatedInjectionMemory());
            return detector.buildPrompt(goals("g1", "g2"),
                    Collections.singletonList(chromosome(1)), 4, 2, null).getMessages();
        });
    }

    @Test
    void stagnationDiagnosticPrompt() throws Exception {
        check("stagnation-diagnostic", () -> {
            Properties.LLM_STAGNATION_PROMPT = Properties.LlmStagnationPromptMode.DIAGNOSTIC;
            StagnationDetector detector = new StagnationDetector(null, 1, 1, new RepeatedInjectionMemory());
            TestFitnessFunction goal = diagnosticGoal();
            // The exception barrier needs a failing attempt in two successive snapshots.
            detector.buildPrompt(Collections.singleton(goal),
                    Collections.singletonList(throwingChromosome()), 4, 2, null);
            PromptResult prompt = detector.buildPrompt(Collections.singleton(goal),
                    Collections.singletonList(throwingChromosome()), 4, 2, null);
            assertNotNull(prompt.getDiagnosticCardTypes());
            return prompt.getMessages();
        });
    }

    @Test
    void asyncProducerPrompts() throws Exception {
        check("async-producer", () -> {
            AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                    Collections::emptyList, null, null, 1, 1, 0, null);
            Collection<TestFitnessFunction> goals = goals("g1", "g2");
            List<TestCase> tests = Collections.singletonList(chromosome(1).getTestCase());
            ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                    .title("Method not reached")
                    .evidence(Collections.singletonList("zero executions"))
                    .build();
            List<LlmMessage> all = new ArrayList<>();
            all.addAll(invoke(producer, "buildPoolPromptForAsync",
                    new Class<?>[]{Collection.class, List.class}, goals, tests));
            all.addAll(invoke(producer, "buildDiagnosticPromptForAsync",
                    new Class<?>[]{Collection.class, List.class, List.class},
                    goals, tests, Collections.singletonList(card)));
            return all;
        });
    }

    private static List<LlmMessage> invoke(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return ((PromptResult) method.invoke(target, args)).getMessages();
    }

    private static TestFitnessFunction diagnosticGoal() {
        String className = DiagnosticTarget.class.getName();
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.getTargetClass()).thenReturn(className);
        when(goal.getTargetMethod()).thenReturn("doWork()V");
        when(goal.toString()).thenReturn(className + ".doWork()V");
        return goal;
    }

    private static TestChromosome throwingChromosome() throws NoSuchMethodException {
        Method reflected = DiagnosticTarget.class.getDeclaredMethod("doWork");
        MethodStatement statement = mock(MethodStatement.class);
        GenericMethod method = mock(GenericMethod.class);
        VariableReference callee = mock(VariableReference.class);
        when(method.getMethod()).thenReturn(reflected);
        when(method.isStatic()).thenReturn(false);
        when(statement.getMethod()).thenReturn(method);
        when(statement.getParameterReferences()).thenReturn(Collections.emptyList());
        doReturn(DiagnosticTarget.class).when(callee).getVariableClass();
        when(statement.getCallee()).thenReturn(callee);

        TestChromosome chromosome = mock(TestChromosome.class);
        TestCase testCase = mock(TestCase.class);
        ExecutionResult result = mock(ExecutionResult.class);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods())
                .thenReturn(Collections.singleton(DiagnosticTarget.class.getName() + ".doWork"));
        when(result.getTrace()).thenReturn(trace);
        when(result.hasTimeout()).thenReturn(false);
        when(result.hasTestException()).thenReturn(true);
        when(result.getFirstPositionOfThrownException()).thenReturn(0);
        when(result.getExceptionThrownAtPosition(0)).thenReturn(new IllegalStateException("ise"));
        when(result.getExecutedStatements()).thenReturn(1);
        when(testCase.toCode()).thenReturn("target.doWork();");
        when(testCase.size()).thenReturn(1);
        when(testCase.hasStatement(0)).thenReturn(true);
        when(testCase.getStatement(0)).thenReturn(statement);
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        when(chromosome.getTestCase()).thenReturn(testCase);
        return chromosome;
    }

    private static final class DiagnosticTarget {
        void doWork() {
            // no-op
        }
    }

    private static Collection<TestFitnessFunction> goals(String... names) {
        List<TestFitnessFunction> goals = new ArrayList<>();
        for (String name : names) {
            goals.add(new StubGoal(name));
        }
        return goals;
    }

    private static TestChromosome chromosome(int marker) {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new IntPrimitiveStatement(testCase, marker));
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);
        chromosome.setLastExecutionResult(new ExecutionResult(testCase));
        chromosome.setChanged(false);
        return chromosome;
    }

    private static LlmService service(LlmService.ChatLanguageModel model) {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE, "mock", "", "", 0.0, 1024, 2, 0, 1, false,
                Paths.get("target/llm-test-traces"), "prompt-golden");
        return new LlmService(model, LlmBudgetCoordinator.of(4), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));
    }

    /** Minimal goal stub with a stable description. */
    private static final class StubGoal extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;
        private final String name;

        StubGoal(String name) {
            this.name = name;
        }

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return individual.getFitnessValues().getOrDefault(this, 1.0);
        }

        @Override
        public ExecutionResult runTest(TestCase test) {
            return new ExecutionResult(test);
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            if (!(other instanceof StubGoal)) {
                return compareClassName(other);
            }
            return name.compareTo(((StubGoal) other).name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StubGoal && name.equals(((StubGoal) other).name);
        }

        @Override
        public String getTargetClass() {
            return "example.Target";
        }

        @Override
        public String getTargetMethod() {
            return name;
        }

        @Override
        public String toString() {
            return "StubGoal[" + name + "]";
        }
    }
}
