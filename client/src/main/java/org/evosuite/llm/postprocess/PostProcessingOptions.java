/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable snapshot of the configuration used by one post-processing phase.
 *
 * <p>The application boundary is the only place where mutable {@link Properties}
 * are read. A running phase keeps and passes this immutable snapshot.</p>
 */
public final class PostProcessingOptions {

    private final Features features;
    private final ContextLimits contextLimits;
    private final AssertionPolicy assertionPolicy;
    private final PhaseBudget phaseBudget;
    private final RepairFallbackPolicy repairFallbackPolicy;
    private final String targetClass;
    private final boolean enabled;
    private final Properties.LlmProvider provider;
    private final int callTimeoutSeconds;
    private final long minimumFreeMemoryBytes;

    private PostProcessingOptions(Features features, ContextLimits contextLimits,
                                  AssertionPolicy assertionPolicy, PhaseBudget phaseBudget,
                                  RepairFallbackPolicy repairFallbackPolicy, String targetClass,
                                  boolean enabled, Properties.LlmProvider provider,
                                  int callTimeoutSeconds, long minimumFreeMemoryBytes) {
        this.features = features;
        this.contextLimits = contextLimits;
        this.assertionPolicy = assertionPolicy;
        this.phaseBudget = phaseBudget;
        this.repairFallbackPolicy = repairFallbackPolicy;
        this.targetClass = targetClass == null ? "" : targetClass;
        this.enabled = enabled;
        this.provider = provider == null ? Properties.LlmProvider.NONE : provider;
        this.callTimeoutSeconds = nonNegative(callTimeoutSeconds);
        this.minimumFreeMemoryBytes = Math.max(0L, minimumFreeMemoryBytes);
    }

    public static PostProcessingOptions fromProperties() {
        return new PostProcessingOptions(
                new Features(Properties.LLM_POSTPROCESSING_ASSERTIONS,
                        Properties.LLM_POSTPROCESSING_TEST_NAMES,
                        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES,
                        Properties.LLM_POSTPROCESSING_COMMENTS,
                        Properties.LLM_POSTPROCESSING_SECTION_BREAKS),
                new ContextLimits(Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS,
                        Properties.LLM_POSTPROCESSING_MAX_CANDIDATE_FACTS,
                        Properties.LLM_POSTPROCESSING_MAX_CANDIDATE_CHARS,
                        Properties.LLM_POSTPROCESSING_MAX_CALLABLE_CHARS,
                        Properties.LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS,
                        Properties.LLM_POSTPROCESSING_MAX_COMMENT_CHARS,
                        Properties.LLM_POSTPROCESSING_MAX_COMMENTS_PER_TEST),
                new AssertionPolicy(Properties.LLM_POSTPROCESSING_MAX_ASSERTIONS_PER_TEST,
                        Properties.LLM_POSTPROCESSING_MAX_CHAIN_DEPTH,
                        Properties.LLM_POSTPROCESSING_CALLABLE_POLICY,
                        Properties.LLM_POSTPROCESSING_ALLOW_CHAINED_CALLS,
                        Properties.LLM_POSTPROCESSING_MAX_CALLABLE_ARGS,
                        Properties.LLM_POSTPROCESSING_MAX_CALLABLE_MEMBERS_PER_TYPE,
                        Properties.LLM_POSTPROCESSING_MAX_CALLABLE_TYPES_PER_TEST,
                        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST,
                        Properties.LLM_POSTPROCESSING_ALLOW_IMMUTABLE_CONSTRUCTORS,
                        Properties.LLM_POSTPROCESSING_IMMUTABLE_TYPES,
                        Properties.LLM_POSTPROCESSING_MAX_EXPRESSION_CHARS,
                        Properties.LLM_POSTPROCESSING_MAX_EXPRESSION_NODES,
                        Properties.LLM_POSTPROCESSING_MAX_CONSTRUCTED_ARRAY_ELEMENTS,
                        Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS,
                        Properties.LLM_POSTPROCESSING_ASSERTION_COMPILE_TIMEOUT_MS,
                        Properties.LLM_POSTPROCESSING_ASSERTION_EVAL_TIMEOUT_MS),
                new PhaseBudget(Properties.LLM_POSTPROCESSING_MAX_TESTS,
                        Properties.LLM_POSTPROCESSING_MAX_TOTAL_STATEMENTS,
                        Properties.LLM_POSTPROCESSING_MAX_CALLS,
                        Properties.LLM_POSTPROCESSING_TIMEOUT,
                        Properties.LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION,
                        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TESTS,
                        Properties.LLM_POSTPROCESSING_LIMITED_MAX_TOTAL_STATEMENTS,
                        Properties.LLM_POSTPROCESSING_LIMITED_MAX_CALLS,
                        Properties.LLM_POSTPROCESSING_SCOPE),
                new RepairFallbackPolicy(Properties.LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS > 0,
                        Properties.LLM_POSTPROCESSING_REPAIR_POLICY,
                        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK,
                        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK_STRATEGY),
                Properties.TARGET_CLASS,
                Properties.LLM_POSTPROCESSING_ENABLED,
                Properties.LLM_PROVIDER,
                Properties.LLM_TIMEOUT_SECONDS,
                Properties.MIN_FREE_MEM);
    }

    public static Builder builder() {
        return new Builder(fromProperties());
    }

    /** Fresh requests always use the winning treatment. */
    public String promptVersion() {
        return LlmPostProcessingProtocol.PRODUCTION_PROMPT_VERSION;
    }

    public int responseSchemaVersion() {
        return LlmPostProcessingProtocol.RESPONSE_SCHEMA_VERSION;
    }

    public Features features() { return features; }
    public ContextLimits contextLimits() { return contextLimits; }
    public AssertionPolicy assertionPolicy() { return assertionPolicy; }
    public PhaseBudget phaseBudget() { return phaseBudget; }
    public RepairFallbackPolicy repairFallbackPolicy() { return repairFallbackPolicy; }
    public String targetClass() { return targetClass; }
    public boolean enabled() { return enabled; }
    public Properties.LlmProvider provider() { return provider; }
    public int callTimeoutSeconds() { return callTimeoutSeconds; }
    public long minimumFreeMemoryBytes() { return minimumFreeMemoryBytes; }

    public static final class Features {
        private final boolean assertions;
        private final boolean testNames;
        private final boolean variableNames;
        private final boolean comments;
        private final boolean sectionBreaks;

        public Features(boolean assertions, boolean testNames, boolean variableNames,
                        boolean comments, boolean sectionBreaks) {
            this.assertions = assertions;
            this.testNames = testNames;
            this.variableNames = variableNames;
            this.comments = comments;
            this.sectionBreaks = sectionBreaks;
        }

        public boolean assertions() { return assertions; }
        public boolean testNames() { return testNames; }
        public boolean variableNames() { return variableNames; }
        public boolean comments() { return comments; }
        public boolean sectionBreaks() { return sectionBreaks; }
        public boolean any() { return assertions || testNames || variableNames || comments || sectionBreaks; }
    }

    public static final class ContextLimits {
        private final int observationChars;
        private final int candidateFacts;
        private final int candidateChars;
        private final int callableChars;
        private final int collectionElements;
        private final int commentChars;
        private final int commentsPerTest;

        public ContextLimits(int observationChars, int candidateFacts, int candidateChars,
                             int callableChars, int collectionElements,
                             int commentChars, int commentsPerTest) {
            this.observationChars = nonNegative(observationChars);
            this.candidateFacts = nonNegative(candidateFacts);
            this.candidateChars = nonNegative(candidateChars);
            this.callableChars = nonNegative(callableChars);
            this.collectionElements = nonNegative(collectionElements);
            this.commentChars = nonNegative(commentChars);
            this.commentsPerTest = nonNegative(commentsPerTest);
        }

        public int observationChars() { return observationChars; }
        public int candidateFacts() { return candidateFacts; }
        public int candidateChars() { return candidateChars; }
        public int callableChars() { return callableChars; }
        public int collectionElements() { return collectionElements; }
        public int commentChars() { return commentChars; }
        public int commentsPerTest() { return commentsPerTest; }
    }

    public static final class AssertionPolicy {
        private final int maxAssertions;
        private final int maxChainDepth;
        private final Properties.LlmPostProcessingCallablePolicy callablePolicy;
        private final boolean allowChainedCalls;
        private final int maxCallableArgs;
        private final int maxCallableMembersPerType;
        private final int maxCallableTypesPerTest;
        private final Set<String> pureStaticAllowlist;
        private final boolean allowImmutableConstructors;
        private final Set<String> immutableTypes;
        private final int maxExpressionChars;
        private final int maxExpressionNodes;
        private final int maxConstructedArrayElements;
        private final int maxLiteralChars;
        private final int compileTimeoutMs;
        private final int evalTimeoutMs;

        public AssertionPolicy(int maxAssertions, int maxChainDepth,
                               Properties.LlmPostProcessingCallablePolicy callablePolicy,
                               boolean allowChainedCalls, int maxCallableArgs,
                               int maxCallableMembersPerType, int maxCallableTypesPerTest,
                               String pureStaticAllowlist, boolean allowImmutableConstructors,
                               String immutableTypes, int maxExpressionChars, int maxExpressionNodes,
                               int maxConstructedArrayElements, int maxLiteralChars,
                               int compileTimeoutMs, int evalTimeoutMs) {
            this.maxAssertions = nonNegative(maxAssertions);
            this.maxChainDepth = nonNegative(maxChainDepth);
            this.callablePolicy = callablePolicy == null
                    ? Properties.LlmPostProcessingCallablePolicy.PURE_BOUNDED : callablePolicy;
            this.allowChainedCalls = allowChainedCalls;
            this.maxCallableArgs = nonNegative(maxCallableArgs);
            this.maxCallableMembersPerType = nonNegative(maxCallableMembersPerType);
            this.maxCallableTypesPerTest = nonNegative(maxCallableTypesPerTest);
            this.pureStaticAllowlist = parseList(pureStaticAllowlist);
            this.allowImmutableConstructors = allowImmutableConstructors;
            this.immutableTypes = parseList(immutableTypes);
            this.maxExpressionChars = nonNegative(maxExpressionChars);
            this.maxExpressionNodes = nonNegative(maxExpressionNodes);
            this.maxConstructedArrayElements = nonNegative(maxConstructedArrayElements);
            this.maxLiteralChars = nonNegative(maxLiteralChars);
            this.compileTimeoutMs = nonNegative(compileTimeoutMs);
            this.evalTimeoutMs = nonNegative(evalTimeoutMs);
        }

        public int maxAssertions() { return maxAssertions; }
        public int maxChainDepth() { return maxChainDepth; }
        public Properties.LlmPostProcessingCallablePolicy callablePolicy() { return callablePolicy; }
        public boolean allowChainedCalls() { return allowChainedCalls; }
        public int maxCallableArgs() { return maxCallableArgs; }
        public int maxCallableMembersPerType() { return maxCallableMembersPerType; }
        public int maxCallableTypesPerTest() { return maxCallableTypesPerTest; }
        public Set<String> pureStaticAllowlist() { return pureStaticAllowlist; }
        public boolean allowImmutableConstructors() { return allowImmutableConstructors; }
        public Set<String> immutableTypes() { return immutableTypes; }
        public int maxExpressionChars() { return maxExpressionChars; }
        public int maxExpressionNodes() { return maxExpressionNodes; }
        public int maxConstructedArrayElements() { return maxConstructedArrayElements; }
        public int maxLiteralChars() { return maxLiteralChars; }
        public int compileTimeoutMs() { return compileTimeoutMs; }
        public int evalTimeoutMs() { return evalTimeoutMs; }
    }

    public static final class PhaseBudget {
        private final int maxTests;
        private final int maxTotalStatements;
        private final int maxCalls;
        private final int timeoutSeconds;
        private final Properties.LlmPostProcessingOnIncompleteMinimization incompletePolicy;
        private final int limitedMaxTests;
        private final int limitedMaxTotalStatements;
        private final int limitedMaxCalls;
        private final Properties.LlmPostProcessingScope scope;

        public PhaseBudget(int maxTests, int maxTotalStatements, int maxCalls, int timeoutSeconds,
                           Properties.LlmPostProcessingOnIncompleteMinimization incompletePolicy,
                           int limitedMaxTests, int limitedMaxTotalStatements, int limitedMaxCalls,
                           Properties.LlmPostProcessingScope scope) {
            this.maxTests = nonNegative(maxTests);
            this.maxTotalStatements = nonNegative(maxTotalStatements);
            this.maxCalls = nonNegative(maxCalls);
            this.timeoutSeconds = nonNegative(timeoutSeconds);
            this.incompletePolicy = incompletePolicy == null
                    ? Properties.LlmPostProcessingOnIncompleteMinimization.SKIP : incompletePolicy;
            this.limitedMaxTests = nonNegative(limitedMaxTests);
            this.limitedMaxTotalStatements = nonNegative(limitedMaxTotalStatements);
            this.limitedMaxCalls = nonNegative(limitedMaxCalls);
            this.scope = scope == null ? Properties.LlmPostProcessingScope.ALL_TESTS : scope;
        }

        public int maxTests() { return maxTests; }
        public int maxTotalStatements() { return maxTotalStatements; }
        public int maxCalls() { return maxCalls; }
        public int timeoutSeconds() { return timeoutSeconds; }
        public Properties.LlmPostProcessingOnIncompleteMinimization incompletePolicy() { return incompletePolicy; }
        public int limitedMaxTests() { return limitedMaxTests; }
        public int limitedMaxTotalStatements() { return limitedMaxTotalStatements; }
        public int limitedMaxCalls() { return limitedMaxCalls; }
        public Properties.LlmPostProcessingScope scope() { return scope; }
    }

    public static final class RepairFallbackPolicy {
        private final boolean assertionRepairEnabled;
        private final Properties.LlmPostProcessingRepairPolicy repairPolicy;
        private final Properties.LlmPostProcessingAssertionFallback fallback;
        private final Properties.LlmPostProcessingAssertionFallbackStrategy fallbackStrategy;

        public RepairFallbackPolicy(boolean assertionRepairEnabled,
                                    Properties.LlmPostProcessingRepairPolicy repairPolicy,
                                    Properties.LlmPostProcessingAssertionFallback fallback,
                                    Properties.LlmPostProcessingAssertionFallbackStrategy fallbackStrategy) {
            this.assertionRepairEnabled = assertionRepairEnabled;
            this.repairPolicy = repairPolicy == null
                    ? Properties.LlmPostProcessingRepairPolicy.TARGETED_ONE : repairPolicy;
            this.fallback = fallback == null
                    ? Properties.LlmPostProcessingAssertionFallback.NONE : fallback;
            this.fallbackStrategy = fallbackStrategy == null
                    ? Properties.LlmPostProcessingAssertionFallbackStrategy.ALL : fallbackStrategy;
        }

        public boolean assertionRepairEnabled() { return assertionRepairEnabled; }

        public Properties.LlmPostProcessingRepairPolicy repairPolicy() { return repairPolicy; }
        public Properties.LlmPostProcessingAssertionFallback fallback() { return fallback; }
        public Properties.LlmPostProcessingAssertionFallbackStrategy fallbackStrategy() { return fallbackStrategy; }
    }

    /** Small builder used by unit tests without mutating global Properties. */
    public static final class Builder {
        private PostProcessingOptions value;

        private Builder(PostProcessingOptions value) { this.value = value; }

        public Builder features(Features features) {
            value = new PostProcessingOptions(features, value.contextLimits, value.assertionPolicy,
                    value.phaseBudget, value.repairFallbackPolicy, value.targetClass,
                    value.enabled, value.provider, value.callTimeoutSeconds,
                    value.minimumFreeMemoryBytes);
            return this;
        }

        public Builder targetClass(String targetClass) {
            value = new PostProcessingOptions(value.features, value.contextLimits, value.assertionPolicy,
                    value.phaseBudget, value.repairFallbackPolicy, targetClass,
                    value.enabled, value.provider, value.callTimeoutSeconds,
                    value.minimumFreeMemoryBytes);
            return this;
        }

        public PostProcessingOptions build() { return value; }
    }

    private static int nonNegative(int value) { return Math.max(0, value); }

    private static Set<String> parseList(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String item : Arrays.asList(value.split(","))) {
            if (item != null && !item.trim().isEmpty()) {
                result.add(item.trim());
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
