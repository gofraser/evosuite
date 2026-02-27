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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmAssertionGeneratorTest {

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

    // ---- Parsing tests ----

    @Test
    void parseAssertions_validAssertions() {
        String response = "assertEquals(42, result);\nassertTrue(flag);\nassertNotNull(obj);";
        List<String> assertions = LlmAssertionGenerator.parseAssertions(response);

        assertEquals(3, assertions.size());
        assertEquals("assertEquals(42, result);", assertions.get(0));
        assertEquals("assertTrue(flag);", assertions.get(1));
        assertEquals("assertNotNull(obj);", assertions.get(2));
    }

    @Test
    void parseAssertions_rejectsNonAssertionCode() {
        String response = "System.out.println(\"hello\");\nint x = 42;\nassertEquals(1, 1);";
        List<String> assertions = LlmAssertionGenerator.parseAssertions(response);

        assertEquals(1, assertions.size());
        assertEquals("assertEquals(1, 1);", assertions.get(0));
    }

    @Test
    void parseAssertions_rejectsUnsafeAssertionCalls() {
        // assertHacked is not a standard assertion
        String response = "assertHacked(true);";
        List<String> assertions = LlmAssertionGenerator.parseAssertions(response);
        assertTrue(assertions.isEmpty());
    }

    @Test
    void parseAssertions_emptyResponse() {
        assertTrue(LlmAssertionGenerator.parseAssertions("").isEmpty());
    }

    @Test
    void parseAssertions_nullResponse() {
        assertTrue(LlmAssertionGenerator.parseAssertions(null).isEmpty());
    }

    @Test
    void parseAssertions_indentedAssertions() {
        String response = "    assertEquals(42, result);";
        List<String> assertions = LlmAssertionGenerator.parseAssertions(response);
        assertEquals(1, assertions.size());
    }

    @Test
    void parseAssertions_assertThrows() {
        String response = "assertThrows(IllegalArgumentException.class, () -> foo.bar());";
        List<String> assertions = LlmAssertionGenerator.parseAssertions(response);
        assertEquals(1, assertions.size());
    }

    // ---- Safety validation ----

    @Test
    void isSafeAssertion_validAssertions() {
        assertTrue(LlmAssertionGenerator.isSafeAssertion("assertEquals(1, 1);"));
        assertTrue(LlmAssertionGenerator.isSafeAssertion("assertTrue(true);"));
        assertTrue(LlmAssertionGenerator.isSafeAssertion("assertFalse(false);"));
        assertTrue(LlmAssertionGenerator.isSafeAssertion("assertNull(null);"));
        assertTrue(LlmAssertionGenerator.isSafeAssertion("assertNotNull(obj);"));
        assertTrue(LlmAssertionGenerator.isSafeAssertion("assertSame(a, b);"));
        assertTrue(LlmAssertionGenerator.isSafeAssertion("assertArrayEquals(a, b);"));
    }

    @Test
    void isSafeAssertion_rejectsInvalid() {
        assertFalse(LlmAssertionGenerator.isSafeAssertion("Runtime.exec(\"rm -rf /\");"));
        assertFalse(LlmAssertionGenerator.isSafeAssertion(""));
        assertFalse(LlmAssertionGenerator.isSafeAssertion(null));
    }

    @Test
    void isSafeAssertion_rejectsMultipleSemicolons() {
        // Multiple statements injected via semicolons
        assertFalse(LlmAssertionGenerator.isSafeAssertion("assertEquals(1, 1); System.exit(0);"));
        assertFalse(LlmAssertionGenerator.isSafeAssertion("assertTrue(true); int x = 1;"));
    }

    @Test
    void isSafeAssertion_rejectsSuspiciousTokens() {
        assertFalse(LlmAssertionGenerator.isSafeAssertion("assertEquals(Runtime.exec(\"cmd\"), x);"));
        assertFalse(LlmAssertionGenerator.isSafeAssertion("assertTrue(System.exit(0) == null);"));
        assertFalse(LlmAssertionGenerator.isSafeAssertion("assertEquals(Class.forName(\"Evil\"), x);"));
    }

    @Test
    void parseAssertions_rejectsInjectionPayloads() {
        // Multi-statement injection attempt
        String response = "assertEquals(1, x); Runtime.getRuntime().exec(\"rm -rf /\");";
        List<String> assertions = LlmAssertionGenerator.parseAssertions(response);
        assertTrue(assertions.isEmpty(), "Should reject multi-statement injection");
    }

    // ---- Mode tests ----

    @Test
    void constructorDefaultsToRegressionMode() {
        LlmAssertionGenerator gen = new LlmAssertionGenerator();
        assertEquals(LlmAssertionGenerator.Mode.REGRESSION, gen.getMode());
    }

    @Test
    void specificationModeConstructor() {
        LlmAssertionGenerator gen = new LlmAssertionGenerator(LlmAssertionGenerator.Mode.SPECIFICATION);
        assertEquals(LlmAssertionGenerator.Mode.SPECIFICATION, gen.getMode());
    }

    // ---- LLM integration ----

    @Test
    void returnsEmptyOnNullTest() {
        LlmAssertionGenerator gen = new LlmAssertionGenerator();
        assertTrue(gen.generateAssertions(null, null).isEmpty());
    }

    @Test
    void returnsEmptyWhenLlmUnavailable() {
        LlmAssertionGenerator gen = new LlmAssertionGenerator();
        TestCase test = new DefaultTestCase();
        ExecutionResult result = new ExecutionResult(test);

        List<String> assertions = gen.generateAssertions(test, result);
        assertTrue(assertions.isEmpty());
    }

    @Test
    void generatesAssertions_whenLlmAvailable() {
        MockChatLanguageModel mockModel = new MockChatLanguageModel();
        mockModel.enqueue(LlmFeature.ASSERTION_GENERATION, "assertEquals(42, result);\nassertTrue(flag);");
        LlmConfiguration config = LlmConfiguration.fromProperties();
        LlmService mockService = new LlmService(
                mockModel,
                new LlmBudgetCoordinator.Local(0),
                config,
                new LlmStatistics(),
                new LlmTraceRecorder(config)
        );
        LlmService.setInstanceForTesting(mockService);

        LlmAssertionGenerator gen = new LlmAssertionGenerator();
        TestCase test = new DefaultTestCase();
        ExecutionResult result = new ExecutionResult(test);

        List<String> assertions = gen.generateAssertions(test, result);
        assertEquals(2, assertions.size());
        assertEquals(2, gen.getAssertionsGenerated());
    }
}
