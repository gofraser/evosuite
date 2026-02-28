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
import org.evosuite.Properties.LlmPromptTechnique;
import org.evosuite.llm.LlmMessage;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FEW_SHOT behavior in {@link PromptBuilder}.
 */
class PromptBuilderFewShotTest {

    private LlmPromptTechnique origTechnique;

    @BeforeEach
    void save() {
        origTechnique = Properties.LLM_PROMPT_TECHNIQUE;
    }

    @AfterEach
    void restore() {
        Properties.LLM_PROMPT_TECHNIQUE = origTechnique;
    }

    private static TestCase makeTestCase(String marker) {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new StringPrimitiveStatement(tc, marker));
        return tc;
    }

    @Test
    void fewShotInjectsExamplesViaWithExistingTests() {
        List<TestCase> examples = Arrays.asList(
                makeTestCase("example1"), makeTestCase("example2"));

        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotExamples(examples)
                .withPromptTechnique(LlmPromptTechnique.FEW_SHOT)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("Existing tests:"),
                "FEW_SHOT should inject examples via withExistingTests");
        assertTrue(userPrompt.contains("example1"),
                "Example content should appear in prompt");
        assertTrue(userPrompt.contains("example2"),
                "Example content should appear in prompt");
    }

    @Test
    void fewShotWithNoExamplesDoesNotCrash() {
        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotExamples(Collections.emptyList())
                .withPromptTechnique(LlmPromptTechnique.FEW_SHOT)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertFalse(userPrompt.contains("Existing tests:"),
                "No examples means no 'Existing tests' section");
        assertTrue(userPrompt.contains("Generate tests."));
    }

    @Test
    void fewShotWithNullExamplesDoesNotCrash() {
        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotExamples(null)
                .withPromptTechnique(LlmPromptTechnique.FEW_SHOT)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertFalse(userPrompt.contains("Existing tests:"));
        assertTrue(userPrompt.contains("Generate tests."));
    }

    @Test
    void fewShotDoesNotAppendStyleHintToSystemPrompt() {
        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotExamples(Collections.singletonList(makeTestCase("x")))
                .withPromptTechnique(LlmPromptTechnique.FEW_SHOT)
                .withInstruction("Generate tests.")
                .build();

        String systemPrompt = messages.get(0).getContent();
        assertFalse(systemPrompt.contains("Few-shot style"),
                "FEW_SHOT should no longer append old style hint to system prompt");
    }

    @Test
    void noneDoesNotInjectAnything() {
        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotExamples(Collections.singletonList(makeTestCase("x")))
                .withPromptTechnique(LlmPromptTechnique.NONE)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertFalse(userPrompt.contains("Existing tests:"));
        assertFalse(userPrompt.contains("Think step by step"));
    }

    @Test
    void chainOfThoughtAddsReasoningHint() {
        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withPromptTechnique(LlmPromptTechnique.CHAIN_OF_THOUGHT)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("Think step by step"));
        assertFalse(userPrompt.contains("Existing tests:"));
    }

    @Test
    void chainOfThoughtUnchangedByFewShotExamples() {
        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotExamples(Collections.singletonList(makeTestCase("x")))
                .withPromptTechnique(LlmPromptTechnique.CHAIN_OF_THOUGHT)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("Think step by step"));
        // Examples not injected under CHAIN_OF_THOUGHT
        assertFalse(userPrompt.contains("Existing tests:"));
    }

    // -- Finding 1: Prompt-level char-budget enforcement via snippets --

    @Test
    void fewShotSnippetsInjectedIntoPrompt() {
        List<String> snippets = Arrays.asList(
                "String s0 = \"example1\";", "String s0 = \"example2\";");

        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotSnippets(snippets)
                .withPromptTechnique(LlmPromptTechnique.FEW_SHOT)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("Existing tests:"),
                "Snippets should appear in 'Existing tests' section");
        assertTrue(userPrompt.contains("example1"));
        assertTrue(userPrompt.contains("example2"));
    }

    @Test
    void fewShotSnippetsRespectPerExampleCap() {
        // Create a test case and use the provider to get truncated snippets
        DefaultTestCase tc = new DefaultTestCase();
        for (int i = 0; i < 20; i++) {
            tc.addStatement(new StringPrimitiveStatement(tc, "longLine_" + i));
        }
        int fullLength = tc.toCode().length();
        int perExampleCap = 50;

        FewShotExampleProvider provider = new FewShotExampleProvider(
                Collections.singletonList(tc), true, false, 3,
                Properties.LlmFewShotArchiveStrategy.GOAL_OVERLAP, 0, perExampleCap);
        List<String> snippets = provider.getExampleSnippets();

        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotSnippets(snippets)
                .withPromptTechnique(LlmPromptTechnique.FEW_SHOT)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("Existing tests:"));
        // The snippet in the prompt should be truncated
        assertTrue(userPrompt.contains("// ... (truncated)"),
                "Truncated snippet should contain truncation marker");
        // Extract the code block content between ```java and ```
        String codeBlock = extractFirstCodeBlock(userPrompt);
        assertTrue(codeBlock.length() < fullLength,
                "Rendered snippet must be shorter than full original code");
    }

    @Test
    void fewShotSnippetsRespectTotalCap() {
        TestCase tc1 = makeTestCase("aaa");
        TestCase tc2 = makeTestCase("bbb");
        TestCase tc3 = makeTestCase("ccc");
        int singleLen = tc1.toCode().length();

        // Total cap fits exactly 1 example
        FewShotExampleProvider provider = new FewShotExampleProvider(
                Arrays.asList(tc1, tc2, tc3), true, false, 10,
                Properties.LlmFewShotArchiveStrategy.GOAL_OVERLAP, singleLen, 0);
        List<String> snippets = provider.getExampleSnippets();

        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotSnippets(snippets)
                .withPromptTechnique(LlmPromptTechnique.FEW_SHOT)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("aaa"),
                "First example should be in prompt");
        assertFalse(userPrompt.contains("bbb"),
                "Second example should not be in prompt (total cap)");
        assertFalse(userPrompt.contains("ccc"),
                "Third example should not be in prompt (total cap)");
    }

    @Test
    void fewShotFirstExampleExceedsTotalCapStillIncluded() {
        DefaultTestCase tc = new DefaultTestCase();
        for (int i = 0; i < 10; i++) {
            tc.addStatement(new StringPrimitiveStatement(tc, "big_" + i));
        }

        // Total cap is tiny, but first example should still be included
        FewShotExampleProvider provider = new FewShotExampleProvider(
                Collections.singletonList(tc), true, false, 3,
                Properties.LlmFewShotArchiveStrategy.GOAL_OVERLAP, 10, 0);
        List<String> snippets = provider.getExampleSnippets();

        assertEquals(1, snippets.size(),
                "First example should be included even if exceeding total cap");

        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotSnippets(snippets)
                .withPromptTechnique(LlmPromptTechnique.FEW_SHOT)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("Existing tests:"),
                "Even oversized first example should produce content");
    }

    @Test
    void fewShotSnippetsPreferredOverTestCases() {
        // When both snippets and TestCases are set, snippets win
        List<String> snippets = Collections.singletonList("// snippet wins");
        List<TestCase> testCases = Collections.singletonList(makeTestCase("testcase_loses"));

        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotExamples(testCases)
                .withFewShotSnippets(snippets)
                .withPromptTechnique(LlmPromptTechnique.FEW_SHOT)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("snippet wins"));
    }

    @Test
    void withExistingTestSnippetsRendersCorrectly() {
        List<String> snippets = Arrays.asList("int x = 1;", "int y = 2;");

        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withExistingTestSnippets(snippets)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("Existing tests:"));
        assertTrue(userPrompt.contains("```java\nint x = 1;\n```"));
        assertTrue(userPrompt.contains("```java\nint y = 2;\n```"));
    }

    @Test
    void withExistingTestSnippetsNullOrEmptyIsNoOp() {
        List<LlmMessage> nullMessages = new PromptBuilder()
                .withSystemPrompt()
                .withExistingTestSnippets(null)
                .withInstruction("Generate tests.")
                .build();
        assertFalse(nullMessages.get(1).getContent().contains("Existing tests:"));

        List<LlmMessage> emptyMessages = new PromptBuilder()
                .withSystemPrompt()
                .withExistingTestSnippets(Collections.emptyList())
                .withInstruction("Generate tests.")
                .build();
        assertFalse(emptyMessages.get(1).getContent().contains("Existing tests:"));
    }

    // -- Regression: non-FEW_SHOT behaviour preserved --

    @Test
    void noneDoesNotInjectSnippets() {
        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotSnippets(Collections.singletonList("// should not appear"))
                .withPromptTechnique(LlmPromptTechnique.NONE)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertFalse(userPrompt.contains("Existing tests:"));
        assertFalse(userPrompt.contains("should not appear"));
    }

    @Test
    void chainOfThoughtDoesNotInjectSnippets() {
        List<LlmMessage> messages = new PromptBuilder()
                .withSystemPrompt()
                .withFewShotSnippets(Collections.singletonList("// should not appear"))
                .withPromptTechnique(LlmPromptTechnique.CHAIN_OF_THOUGHT)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertFalse(userPrompt.contains("Existing tests:"));
        assertTrue(userPrompt.contains("Think step by step"));
    }

    private static String extractFirstCodeBlock(String text) {
        int start = text.indexOf("```java\n");
        if (start < 0) return "";
        start += "```java\n".length();
        int end = text.indexOf("\n```", start);
        if (end < 0) return text.substring(start);
        return text.substring(start, end);
    }
}
