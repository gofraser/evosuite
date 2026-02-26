package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LlmTestNameGeneratorTest {

    @BeforeEach
    void setUp() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        Properties.TARGET_CLASS = "com.example.Foo";
        LlmService.resetInstanceForTesting();
    }

    @AfterEach
    void tearDown() {
        LlmService.resetInstanceForTesting();
    }

    private LlmService createMockService(String... responses) {
        MockChatLanguageModel mockModel = new MockChatLanguageModel();
        for (String resp : responses) {
            mockModel.enqueue(LlmFeature.TEST_NAMING, resp);
        }
        LlmConfiguration config = LlmConfiguration.fromProperties();
        return new LlmService(mockModel,
                new LlmBudgetCoordinator.Local(0),
                config,
                new LlmStatistics(),
                new LlmTraceRecorder(config));
    }

    // ---- Parsing tests ----

    @Test
    void parseNameResponse_validNumberedList() {
        List<TestCase> tests = List.of(new DefaultTestCase(), new DefaultTestCase(), new DefaultTestCase());
        String response = "1. shouldReturnTrue\n2. handlesNullInput\n3. throwsOnInvalid";

        Map<TestCase, String> names = LlmTestNameGenerator.parseNameResponse(response, tests);

        assertEquals(3, names.size());
        assertEquals("shouldReturnTrue", names.get(tests.get(0)));
        assertEquals("handlesNullInput", names.get(tests.get(1)));
        assertEquals("throwsOnInvalid", names.get(tests.get(2)));
    }

    @Test
    void parseNameResponse_withColonSeparator() {
        List<TestCase> tests = List.of(new DefaultTestCase(), new DefaultTestCase());
        String response = "1: testMethodA\n2: testMethodB";

        Map<TestCase, String> names = LlmTestNameGenerator.parseNameResponse(response, tests);
        assertEquals(2, names.size());
    }

    @Test
    void parseNameResponse_withBackticks() {
        List<TestCase> tests = List.of(new DefaultTestCase());
        String response = "1. `shouldReturnTrue`";

        Map<TestCase, String> names = LlmTestNameGenerator.parseNameResponse(response, tests);
        assertEquals(1, names.size());
        assertEquals("shouldReturnTrue", names.get(tests.get(0)));
    }

    @Test
    void parseNameResponse_ignoresInvalidNames() {
        List<TestCase> tests = List.of(new DefaultTestCase());
        // Invalid: starts with uppercase
        String response = "1. InvalidName";

        Map<TestCase, String> names = LlmTestNameGenerator.parseNameResponse(response, tests);
        assertTrue(names.isEmpty());
    }

    @Test
    void parseNameResponse_ignoresOutOfBoundsIndices() {
        List<TestCase> tests = List.of(new DefaultTestCase());
        String response = "1. validName\n5. outOfBoundsName";

        Map<TestCase, String> names = LlmTestNameGenerator.parseNameResponse(response, tests);
        assertEquals(1, names.size());
    }

    @Test
    void parseNameResponse_emptyResponse() {
        List<TestCase> tests = List.of(new DefaultTestCase());
        Map<TestCase, String> names = LlmTestNameGenerator.parseNameResponse("", tests);
        assertTrue(names.isEmpty());
    }

    @Test
    void parseNameResponse_nullResponse() {
        List<TestCase> tests = List.of(new DefaultTestCase());
        Map<TestCase, String> names = LlmTestNameGenerator.parseNameResponse(null, tests);
        assertTrue(names.isEmpty());
    }

    // ---- Validation tests ----

    @Test
    void isValidMethodName_validCamelCase() {
        assertTrue(LlmTestNameGenerator.isValidMethodName("shouldReturnTrue"));
        assertTrue(LlmTestNameGenerator.isValidMethodName("testMethod"));
        assertTrue(LlmTestNameGenerator.isValidMethodName("a"));
    }

    @Test
    void isValidMethodName_rejectsUppercaseStart() {
        assertFalse(LlmTestNameGenerator.isValidMethodName("ShouldReturnTrue"));
    }

    @Test
    void isValidMethodName_rejectsKeywords() {
        assertFalse(LlmTestNameGenerator.isValidMethodName("class"));
        assertFalse(LlmTestNameGenerator.isValidMethodName("return"));
        assertFalse(LlmTestNameGenerator.isValidMethodName("null"));
    }

    @Test
    void isValidMethodName_rejectsEmpty() {
        assertFalse(LlmTestNameGenerator.isValidMethodName(""));
        assertFalse(LlmTestNameGenerator.isValidMethodName(null));
    }

    // ---- Integration: fallback behavior when LLM unavailable ----

    @Test
    void fallsBackToCoverageGoalNaming_whenLlmUnavailable() {
        // With NONE provider, LLM is unavailable
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;

        TestCase test = new DefaultTestCase();
        ExecutionResult result = new ExecutionResult(test);
        List<TestCase> tests = List.of(test);
        List<ExecutionResult> results = List.of(result);

        LlmTestNameGenerator gen = new LlmTestNameGenerator(tests, results);

        String name = gen.getName(test);
        assertNotNull(name);
        assertFalse(name.isEmpty());
        assertEquals(0, gen.getTestsRenamed());
        assertEquals(1, gen.getFallbacks());
    }

    @Test
    void usesLlmNames_whenAvailable() {
        LlmService.setInstanceForTesting(createMockService("1. shouldComputeCorrectly"));

        TestCase test = new DefaultTestCase();
        ExecutionResult result = new ExecutionResult(test);
        List<TestCase> tests = List.of(test);
        List<ExecutionResult> results = List.of(result);

        LlmTestNameGenerator gen = new LlmTestNameGenerator(tests, results);

        assertEquals("shouldComputeCorrectly", gen.getName(test));
        assertEquals(1, gen.getTestsRenamed());
        assertEquals(0, gen.getFallbacks());
    }

    @Test
    void deduplicatesNames_fallsBackOnDuplicate() {
        LlmService.setInstanceForTesting(createMockService("1. sameName\n2. sameName"));

        TestCase test1 = new DefaultTestCase();
        TestCase test2 = new DefaultTestCase();
        ExecutionResult result1 = new ExecutionResult(test1);
        ExecutionResult result2 = new ExecutionResult(test2);
        List<TestCase> tests = List.of(test1, test2);
        List<ExecutionResult> results = List.of(result1, result2);

        LlmTestNameGenerator gen = new LlmTestNameGenerator(tests, results);

        assertEquals("sameName", gen.getName(test1));
        // Second test should have fallback name since duplicate
        String name2 = gen.getName(test2);
        assertNotEquals("sameName", name2);
        assertEquals(1, gen.getTestsRenamed());
        assertEquals(1, gen.getFallbacks());
    }
}
