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
import org.evosuite.llm.LlmMessage;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Fluent prompt builder for EvoSuite LLM requests.
 */
public class PromptBuilder {

    private final SystemPromptProvider systemPromptProvider;
    private final TestClusterSummarizer testClusterSummarizer;
    private final SourceCodeProvider sourceCodeProvider;
    private final CoverageGoalFormatter coverageGoalFormatter;
    private final TestCaseFormatter testCaseFormatter;
    private final SutContextProviderFactory sutContextProviderFactory;

    private String systemPrompt;
    private final List<String> userSections = new ArrayList<>();
    private Properties.LlmSutContextMode sutContextModeUsed;
    private boolean sutContextUnavailable;

    /** Creates a builder with default provider implementations. */
    public PromptBuilder() {
        this(new SystemPromptProvider(),
                new TestClusterSummarizer(),
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                SutContextProviderFactory.getInstance());
    }

    /**
     * Creates a builder using the default {@link SutContextProviderFactory} singleton.
     */
    public PromptBuilder(SystemPromptProvider systemPromptProvider,
                         TestClusterSummarizer testClusterSummarizer,
                         SourceCodeProvider sourceCodeProvider,
                         CoverageGoalFormatter coverageGoalFormatter,
                         TestCaseFormatter testCaseFormatter) {
        this(systemPromptProvider, testClusterSummarizer, sourceCodeProvider,
                coverageGoalFormatter, testCaseFormatter, new SutContextProviderFactory());
    }

    /** Creates a builder with all providers and factory explicitly specified. */
    public PromptBuilder(SystemPromptProvider systemPromptProvider,
                         TestClusterSummarizer testClusterSummarizer,
                         SourceCodeProvider sourceCodeProvider,
                         CoverageGoalFormatter coverageGoalFormatter,
                         TestCaseFormatter testCaseFormatter,
                         SutContextProviderFactory sutContextProviderFactory) {
        this.systemPromptProvider = systemPromptProvider;
        this.testClusterSummarizer = testClusterSummarizer;
        this.sourceCodeProvider = sourceCodeProvider;
        this.coverageGoalFormatter = coverageGoalFormatter;
        this.testCaseFormatter = testCaseFormatter;
        this.sutContextProviderFactory = sutContextProviderFactory;
    }

    public PromptBuilder withSystemPrompt() {
        this.systemPrompt = systemPromptProvider.getSystemPrompt();
        return this;
    }

    /**
     * Add CUT context using the configured {@code LLM_SUT_CONTEXT_MODE}.
     * This replaces separate {@code withTestClusterContext} and {@code withSourceCode} calls.
     */
    public PromptBuilder withSutContext(String className, TestCluster cluster) {
        SutContextProviderFactory.ContextResult result =
                sutContextProviderFactory.getContext(className, cluster);
        this.sutContextModeUsed = result.getModeUsed();
        this.sutContextUnavailable = result.isContextUnavailable();
        String text = result.getText();
        if (text != null && !text.trim().isEmpty()) {
            userSections.add(result.getModeUsed().name() + " context:\n```\n" + text + "\n```");
            userSections.add("IMPORTANT: Ensure that all generic types "
                    + "(e.g., Vector<Character>, List<String>) match the "
                    + "class definition exactly. Do not use generic types like Vector<String> if the class "
                    + "expects Vector<Character>.");
        }
        return this;
    }

    /** Adds test cluster context (available API signatures) to the user prompt. */
    public PromptBuilder withTestClusterContext(TestCluster cluster) {
        userSections.add("Available API context:\n" + testClusterSummarizer.summarize(cluster));
        return this;
    }

    /** Adds the source code of the given class to the user prompt when available. */
    public PromptBuilder withSourceCode(String className) {
        Optional<String> sourceCode = sourceCodeProvider.getSourceCode(className);
        if (sourceCode.isPresent()) {
            userSections.add("Source code:\n```java\n" + sourceCode.get() + "\n```");
        }
        return this;
    }

    /** Adds the given uncovered coverage goals as a section in the user prompt. */
    public PromptBuilder withUncoveredGoals(Collection<TestFitnessFunction> goals) {
        userSections.add("Uncovered goals:\n" + coverageGoalFormatter.format(goals));
        return this;
    }

    /** Adds an existing test case to the user prompt as context. */
    public PromptBuilder withExistingTest(TestCase test) {
        userSections.add("Existing test:\n```java\n" + testCaseFormatter.format(test) + "\n```");
        return this;
    }

    /** Adds multiple existing test cases to the user prompt as context. */
    public PromptBuilder withExistingTests(List<TestCase> tests) {
        if (tests == null || tests.isEmpty()) {
            return this;
        }
        StringBuilder builder = new StringBuilder("Existing tests:\n");
        for (TestCase test : tests) {
            builder.append("```java\n")
                    .append(testCaseFormatter.format(test))
                    .append("\n```\n");
        }
        userSections.add(builder.toString());
        return this;
    }

    public PromptBuilder withError(String errorMessage) {
        userSections.add("Repair error context:\n" + errorMessage);
        return this;
    }

    /** Adds a free-form instruction string as the final user prompt section. */
    public PromptBuilder withInstruction(String instruction) {
        userSections.add(instruction);
        return this;
    }

    /** Applies the specified prompt technique to the user prompt. */
    public PromptBuilder withPromptTechnique(Properties.LlmPromptTechnique technique) {
        if (technique == null || technique == Properties.LlmPromptTechnique.NONE) {
            return this;
        }
        if (technique == Properties.LlmPromptTechnique.CHAIN_OF_THOUGHT) {
            userSections.add("Think step by step and then provide only final Java code.");
        }
        if (technique == Properties.LlmPromptTechnique.FEW_SHOT) {
            String addition = "Few-shot style: prefer concise assertion-driven tests with clear setup/act/assert flow.";
            if (systemPrompt == null) {
                systemPrompt = systemPromptProvider.getSystemPrompt();
            }
            systemPrompt = systemPrompt + "\n" + addition;
        }
        return this;
    }

    public List<LlmMessage> build() {
        return buildWithMetadata().getMessages();
    }

    /**
     * Builds the prompt messages along with context metadata for trace recording.
     * Call sites that need to propagate context mode to LlmService should use this
     * instead of {@link #build()}.
     */
    public PromptResult buildWithMetadata() {
        String resolvedSystem = systemPrompt == null ? systemPromptProvider.getSystemPrompt() : systemPrompt;
        String userPrompt = String.join("\n\n", userSections);
        if (userPrompt.trim().isEmpty()) {
            userPrompt = "Generate one valid Java test method.";
        }

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(resolvedSystem));
        messages.add(LlmMessage.user(userPrompt));
        return new PromptResult(messages, sutContextModeUsed, sutContextUnavailable);
    }

    /**
     * Returns the context mode used by the last {@link #withSutContext} call, or null if not called.
     */
    public Properties.LlmSutContextMode getSutContextModeUsed() {
        return sutContextModeUsed;
    }

    /** Returns true if context was unavailable (strict mode, no fallback). */
    public boolean isSutContextUnavailable() {
        return sutContextUnavailable;
    }
}
