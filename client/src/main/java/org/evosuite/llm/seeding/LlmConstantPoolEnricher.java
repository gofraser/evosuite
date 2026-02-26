package org.evosuite.llm.seeding;

import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.setup.TestCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches EvoSuite's constant pool with LLM-suggested edge-case literals.
 * Designed for async invocation with graceful failure handling.
 */
public class LlmConstantPoolEnricher extends AbstractLlmEnricher<LlmConstantPoolEnricher.EnrichmentResult> {

    // Patterns for extracting typed constants from LLM response
    static final Pattern STRING_PATTERN = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    static final Pattern INT_PATTERN = Pattern.compile(
            "(?:^|[\\s,;:=\\[({])(-?\\d{1,10})(?=[\\s,;:=\\])}]|$)", Pattern.MULTILINE);
    static final Pattern LONG_PATTERN = Pattern.compile(
            "(?:^|[\\s,;:=\\[({])(-?\\d+)[Ll](?=[\\s,;:=\\])}]|$)", Pattern.MULTILINE);
    static final Pattern DOUBLE_PATTERN = Pattern.compile(
            "(?:^|[\\s,;:=\\[({])(-?\\d+\\.\\d+(?:[eE][+-]?\\d+)?)[dD]?(?=[\\s,;:=\\])}]|$)", Pattern.MULTILINE);
    static final Pattern FLOAT_PATTERN = Pattern.compile(
            "(?:^|[\\s,;:=\\[({])(-?\\d+\\.\\d+(?:[eE][+-]?\\d+)?)[fF](?=[\\s,;:=\\])}]|$)", Pattern.MULTILINE);
    static final Pattern NAN_PATTERN = Pattern.compile("(?i)\\b(?:double\\.)?nan\\b");
    static final Pattern INFINITY_PATTERN = Pattern.compile("(?i)(?:double\\.)?(positive_|negative_)?([+-])?infinity\\b");

    public LlmConstantPoolEnricher(LlmService llmService) {
        super(llmService, LlmFeature.CONSTANT_POOL_ENRICHMENT);
    }

    /**
     * Synchronous enrichment logic (called by base class async template).
     */
    @Override
    protected EnrichmentResult doEnrich(String className, TestCluster cluster) {
        PromptResult promptResult = buildPrompt(className, cluster);
        String response = llmService.query(promptResult, feature);

        List<Object> constants = parseConstants(response);
        int sutParsed = constants.size();
        int sutAdded = addToPool(constants, true);
        int nonSutParsed = 0;
        int nonSutAdded = 0;

        if (Properties.LLM_ENRICH_NON_SUT_CONSTANT_POOL && cluster != null) {
            List<String> dependencies = collectNonSutDependencyClasses(className, cluster);
            for (String dependencyClass : dependencies) {
                if (isCancelled()) {
                    logger.debug("Cancelled during non-SUT constant enrichment, stopping");
                    break;
                }
                if (!llmService.hasBudget()) {
                    logger.debug("Budget exhausted during non-SUT constant enrichment, stopping early");
                    break;
                }
                try {
                    PromptResult depPrompt = buildDependencyPrompt(className, dependencyClass, cluster);
                    String depResponse = llmService.query(depPrompt, feature);
                    List<Object> depConstants = capDependencyConstants(parseConstants(depResponse));
                    nonSutParsed += depConstants.size();
                    nonSutAdded += addToPool(depConstants, false);
                } catch (Throwable t) {
                    logger.debug("Failed non-SUT constant enrichment for {}: {}", dependencyClass, t.getMessage());
                }
            }
            logger.info("Non-SUT constant enrichment: classes={}, parsed={}, added={}",
                    dependencies.size(), nonSutParsed, nonSutAdded);
        }

        logger.info("Constant pool enrichment: sutParsed={}, sutAdded={}, nonSutParsed={}, nonSutAdded={}",
                sutParsed, sutAdded, nonSutParsed, nonSutAdded);
        return new EnrichmentResult(true, sutAdded, nonSutAdded, sutParsed + nonSutParsed, null);
    }

    @Override
    protected EnrichmentResult createSkippedResult(String reason) {
        return EnrichmentResult.skipped(reason);
    }

    @Override
    protected EnrichmentResult createFailureResult(String reason) {
        return EnrichmentResult.failure(reason);
    }

    PromptResult buildPrompt(String className, TestCluster cluster) {
        PromptBuilder builder = new PromptBuilder();
        builder.withSystemPrompt()
                .withSutContext(className, cluster)
                .withInstruction(
                        "For testing the class " + className + ", suggest useful constant values that would exercise " +
                        "edge cases, boundary conditions, and interesting code paths.\n\n" +
                        "Provide constants as a list of typed literals. Include:\n" +
                        "- Strings: edge-case strings (empty, whitespace, special chars, long, Unicode, SQL, paths)\n" +
                        "- Integers: boundary values (0, -1, 1, Integer.MAX_VALUE, Integer.MIN_VALUE, powers of 2)\n" +
                        "- Longs: boundary values with L suffix (0L, -1L, Long.MAX_VALUE)\n" +
                        "- Doubles: boundary values (0.0, -0.0, 1.0, Double.MAX_VALUE, NaN, Infinity)\n" +
                        "- Floats: boundary values with f suffix (0.0f, 1.0f, Float.MAX_VALUE)\n\n" +
                        "Format each constant on its own line as a Java literal. Example:\n" +
                        "\"\" \n" +
                        "\"hello world\"\n" +
                        "0\n" +
                        "-1\n" +
                        "2147483647\n" +
                        "0L\n" +
                        "3.14\n" +
                        "1.0f\n\n" +
                        "Only provide the literal values, no explanations needed.")
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE);
        return builder.buildWithMetadata();
    }

    PromptResult buildDependencyPrompt(String sutClassName, String dependencyClassName, TestCluster cluster) {
        PromptBuilder builder = new PromptBuilder();
        builder.withSystemPrompt()
                .withSutContext(sutClassName, cluster)
                .withInstruction(
                        "For testing class " + sutClassName + ", suggest useful constant values specifically for " +
                        "interactions with dependency class " + dependencyClassName + ".\n\n" +
                        "Focus on dependency-facing values (ids, keys, paths, protocol tokens, numeric boundaries).\n" +
                        "Provide typed Java literals only, one per line.\n" +
                        "Do not include explanations.")
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE);
        return builder.buildWithMetadata();
    }

    List<String> collectNonSutDependencyClasses(String sutClassName, TestCluster cluster) {
        Set<String> unique = new LinkedHashSet<>();
        try {
            Set<Class<?>> analyzed = cluster.getAnalyzedClasses();
            if (analyzed != null) {
                for (Class<?> clazz : analyzed) {
                    if (clazz == null || clazz.isPrimitive() || clazz.isArray()) {
                        continue;
                    }
                    String name = clazz.getName();
                    if (name.equals(sutClassName)) {
                        continue;
                    }
                    if (name.startsWith("java.lang.")) {
                        continue;
                    }
                    unique.add(name);
                    if (unique.size() >= Math.max(1, Properties.LLM_NON_SUT_CONSTANT_CLASSES_MAX)) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            logger.debug("Failed to collect non-SUT dependency classes: {}", t.getMessage());
        }
        return new ArrayList<>(unique);
    }

    List<Object> capDependencyConstants(List<Object> parsedConstants) {
        int max = Math.max(1, Properties.LLM_NON_SUT_CONSTANTS_PER_CLASS_MAX);
        if (parsedConstants.size() <= max) {
            return parsedConstants;
        }
        return new ArrayList<>(parsedConstants.subList(0, max));
    }

    /**
     * Parses typed constants from LLM response text.
     * Handles strings, ints, longs, doubles, and floats.
     */
    static List<Object> parseConstants(String response) {
        List<Object> constants = new ArrayList<>();
        if (response == null || response.trim().isEmpty()) {
            return constants;
        }

        // Parse strings
        Matcher stringMatcher = STRING_PATTERN.matcher(response);
        while (stringMatcher.find()) {
            String value = unescapeJavaString(stringMatcher.group(1));
            constants.add(value);
        }

        // Parse floats (before doubles since float suffix is more specific)
        Matcher floatMatcher = FLOAT_PATTERN.matcher(response);
        while (floatMatcher.find()) {
            try {
                constants.add(Float.parseFloat(floatMatcher.group(1)));
            } catch (NumberFormatException e) {
                // skip malformed
            }
        }

        // Parse longs (before ints since long suffix is more specific)
        Matcher longMatcher = LONG_PATTERN.matcher(response);
        while (longMatcher.find()) {
            try {
                constants.add(Long.parseLong(longMatcher.group(1)));
            } catch (NumberFormatException e) {
                // skip malformed
            }
        }

        // Parse doubles (numbers with decimal point but no f suffix)
        Matcher doubleMatcher = DOUBLE_PATTERN.matcher(response);
        while (doubleMatcher.find()) {
            try {
                constants.add(Double.parseDouble(doubleMatcher.group(1)));
            } catch (NumberFormatException e) {
                // skip malformed
            }
        }

        // Parse special double values from text
        Matcher nanMatcher = NAN_PATTERN.matcher(response);
        if (nanMatcher.find()) {
            constants.add(Double.NaN);
        }

        Matcher infinityMatcher = INFINITY_PATTERN.matcher(response);
        while (infinityMatcher.find()) {
            String qualifier = infinityMatcher.group(1);
            String sign = infinityMatcher.group(2);
            if ("negative_".equalsIgnoreCase(qualifier) || "-".equals(sign)) {
                constants.add(Double.NEGATIVE_INFINITY);
            } else {
                constants.add(Double.POSITIVE_INFINITY);
            }
        }

        // Parse ints (numbers without decimal or suffix, not already parsed as long)
        Matcher intMatcher = INT_PATTERN.matcher(response);
        while (intMatcher.find()) {
            try {
                long val = Long.parseLong(intMatcher.group(1));
                if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                    constants.add((int) val);
                }
            } catch (NumberFormatException e) {
                // skip malformed
            }
        }

        // Deduplicate while preserving order
        return new ArrayList<>(new LinkedHashSet<>(constants));
    }

    private int addToPool(List<Object> constants, boolean sutPool) {
        ConstantPoolManager poolManager = ConstantPoolManager.getInstance();
        int added = 0;
        for (Object constant : constants) {
            try {
                if (sutPool) {
                    poolManager.addSUTConstant(constant);
                } else {
                    poolManager.addNonSUTConstant(constant);
                }
                added++;
            } catch (Throwable t) {
                logger.debug("Failed to add constant to pool: {}", t.getMessage());
            }
        }
        return added;
    }

    static String unescapeJavaString(String escaped) {
        if (escaped == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 1 < escaped.length()) {
                char next = escaped.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '"': sb.append('"'); i++; break;
                    case '\'': sb.append('\''); i++; break;
                    case '0': sb.append('\0'); i++; break;
                    default: sb.append(c); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Result of an enrichment attempt.
     */
    public static class EnrichmentResult extends AbstractLlmEnricher.EnrichmentResult {
        private final int sutConstantsAdded;
        private final int nonSutConstantsAdded;
        private final int constantsParsed;

        public EnrichmentResult(boolean attempted, int sutConstantsAdded, int nonSutConstantsAdded,
                                int constantsParsed, String failureReason) {
            super(attempted, failureReason);
            this.sutConstantsAdded = sutConstantsAdded;
            this.nonSutConstantsAdded = nonSutConstantsAdded;
            this.constantsParsed = constantsParsed;
        }

        static EnrichmentResult skipped(String reason) {
            return new EnrichmentResult(false, 0, 0, 0, reason);
        }

        static EnrichmentResult failure(String reason) {
            return new EnrichmentResult(true, 0, 0, 0, reason);
        }

        /** Total constants added (SUT + non-SUT). */
        public int getConstantsAdded() {
            return sutConstantsAdded + nonSutConstantsAdded;
        }

        public int getSutConstantsAdded() {
            return sutConstantsAdded;
        }

        public int getNonSutConstantsAdded() {
            return nonSutConstantsAdded;
        }

        public int getConstantsParsed() {
            return constantsParsed;
        }
    }
}
