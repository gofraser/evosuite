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

    private String systemPrompt;
    private final List<String> userSections = new ArrayList<>();

    public PromptBuilder() {
        this(new SystemPromptProvider(),
                new TestClusterSummarizer(),
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter());
    }

    public PromptBuilder(SystemPromptProvider systemPromptProvider,
                         TestClusterSummarizer testClusterSummarizer,
                         SourceCodeProvider sourceCodeProvider,
                         CoverageGoalFormatter coverageGoalFormatter,
                         TestCaseFormatter testCaseFormatter) {
        this.systemPromptProvider = systemPromptProvider;
        this.testClusterSummarizer = testClusterSummarizer;
        this.sourceCodeProvider = sourceCodeProvider;
        this.coverageGoalFormatter = coverageGoalFormatter;
        this.testCaseFormatter = testCaseFormatter;
    }

    public PromptBuilder withSystemPrompt() {
        this.systemPrompt = systemPromptProvider.getSystemPrompt();
        return this;
    }

    public PromptBuilder withTestClusterContext(TestCluster cluster) {
        userSections.add("Available API context:\n" + testClusterSummarizer.summarize(cluster));
        return this;
    }

    public PromptBuilder withSourceCode(String className) {
        Optional<String> sourceCode = sourceCodeProvider.getSourceCode(className);
        if (sourceCode.isPresent()) {
            userSections.add("Source code:\n```java\n" + sourceCode.get() + "\n```");
        }
        return this;
    }

    public PromptBuilder withUncoveredGoals(Collection<TestFitnessFunction> goals) {
        userSections.add("Uncovered goals:\n" + coverageGoalFormatter.format(goals));
        return this;
    }

    public PromptBuilder withExistingTest(TestCase test) {
        userSections.add("Existing test:\n```java\n" + testCaseFormatter.format(test) + "\n```");
        return this;
    }

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

    public PromptBuilder withInstruction(String instruction) {
        userSections.add(instruction);
        return this;
    }

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
        String resolvedSystem = systemPrompt == null ? systemPromptProvider.getSystemPrompt() : systemPrompt;
        String userPrompt = String.join("\n\n", userSections);
        if (userPrompt.trim().isEmpty()) {
            userPrompt = "Generate one valid Java test method.";
        }

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(resolvedSystem));
        messages.add(LlmMessage.user(userPrompt));
        return messages;
    }
}
