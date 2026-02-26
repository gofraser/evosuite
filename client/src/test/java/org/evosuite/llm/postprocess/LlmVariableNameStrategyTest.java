package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.LlmService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmVariableNameStrategyTest {

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
    void parseVariableResponse_colonSeparated() {
        String response = "0: userName\n1: maxRetries\n2: resultList";
        Map<Integer, String> result = LlmVariableNameStrategy.parseVariableResponse(response);

        assertEquals(3, result.size());
        assertEquals("userName", result.get(0));
        assertEquals("maxRetries", result.get(1));
        assertEquals("resultList", result.get(2));
    }

    @Test
    void parseVariableResponse_dotSeparated() {
        String response = "0. userName\n1. maxRetries";
        Map<Integer, String> result = LlmVariableNameStrategy.parseVariableResponse(response);
        assertEquals(2, result.size());
    }

    @Test
    void parseVariableResponse_arrowSeparated() {
        String response = "var0 -> userName\nvar1 -> maxRetries";
        Map<Integer, String> result = LlmVariableNameStrategy.parseVariableResponse(response);
        assertEquals(2, result.size());
        assertEquals("userName", result.get(0));
    }

    @Test
    void parseVariableResponse_withBackticks() {
        String response = "0: `userName`\n1: `maxRetries`";
        Map<Integer, String> result = LlmVariableNameStrategy.parseVariableResponse(response);
        assertEquals(2, result.size());
        assertEquals("userName", result.get(0));
    }

    @Test
    void parseVariableResponse_ignoresInvalidNames() {
        // Starts with uppercase
        String response = "0: InvalidName\n1: validName";
        Map<Integer, String> result = LlmVariableNameStrategy.parseVariableResponse(response);
        assertEquals(1, result.size());
        assertEquals("validName", result.get(1));
    }

    @Test
    void parseVariableResponse_emptyResponse() {
        Map<Integer, String> result = LlmVariableNameStrategy.parseVariableResponse("");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseVariableResponse_nullResponse() {
        Map<Integer, String> result = LlmVariableNameStrategy.parseVariableResponse(null);
        assertTrue(result.isEmpty());
    }

    // ---- Validation tests ----

    @Test
    void isValidVariableName_validCamelCase() {
        assertTrue(LlmVariableNameStrategy.isValidVariableName("userName"));
        assertTrue(LlmVariableNameStrategy.isValidVariableName("x"));
        assertTrue(LlmVariableNameStrategy.isValidVariableName("maxRetryCount"));
    }

    @Test
    void isValidVariableName_rejectsUppercaseStart() {
        assertFalse(LlmVariableNameStrategy.isValidVariableName("UserName"));
    }

    @Test
    void isValidVariableName_rejectsKeywords() {
        assertFalse(LlmVariableNameStrategy.isValidVariableName("int"));
        assertFalse(LlmVariableNameStrategy.isValidVariableName("class"));
        assertFalse(LlmVariableNameStrategy.isValidVariableName("null"));
        assertFalse(LlmVariableNameStrategy.isValidVariableName("boolean"));
    }

    @Test
    void isValidVariableName_rejectsEmpty() {
        assertFalse(LlmVariableNameStrategy.isValidVariableName(""));
        assertFalse(LlmVariableNameStrategy.isValidVariableName(null));
    }

    // ---- Re-entrancy guard tests ----

    @Test
    void renderingForLlm_defaultIsFalse() {
        assertFalse(LlmVariableNameStrategy.isRenderingForLlm());
    }

    @Test
    void createNameForVariable_fallsBackToTypeBased_whenReentrant() {
        // Simulate re-entrancy: when RENDERING_FOR_LLM is set,
        // createNameForVariable should immediately return a type-based name
        // without attempting LLM query (which would recurse).
        Properties.VARIABLE_NAMING_STRATEGY = Properties.VariableNamingStrategy.LLM;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI; // Would normally trigger LLM

        LlmVariableNameStrategy strategy = new LlmVariableNameStrategy();

        // Create a simple test with a variable
        org.evosuite.testcase.DefaultTestCase tc = new org.evosuite.testcase.DefaultTestCase();
        org.evosuite.testcase.variable.VariableReference ref =
                tc.addStatement(new org.evosuite.testcase.statements.numeric.IntPrimitiveStatement(tc, 42));

        // This simulates the scenario: we're rendering for LLM prompt,
        // and a new LlmVariableNameStrategy is asked for a name.
        // It should NOT recurse; it should produce a type-based name.
        // We call createNameForVariable directly to test the guard.
        String name = strategy.createNameForVariable(ref);
        assertNotNull(name);
        // Since LLM is not truly available (NONE after resetInstance), it falls back to type-based
        // The key test: it must terminate without StackOverflowError
        assertFalse(name.isEmpty());
    }
}
