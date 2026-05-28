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
package org.evosuite.llm.seeding;

import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.rmi.ClientServices;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.setup.TestCluster;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.utils.generic.GenericAccessibleObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Enriches EvoSuite's constant pool with LLM-suggested edge-case literals.
 * Designed for async invocation with graceful failure handling.
 */
public class LlmConstantPoolEnricher extends AbstractLlmEnricher<LlmConstantPoolEnricher.EnrichmentResult> {

    // Types that parseConstants can actually produce AND that StaticConstantPool
    // can store. boolean/char/byte/short have no parser path and no pool slot —
    // surfacing them in the param-type digest would mislead the LLM into emitting
    // tokens the parser silently drops.
    private static final Set<Class<?>> CONSTANT_COMPATIBLE_TYPES = new HashSet<>(Arrays.<Class<?>>asList(
            String.class, int.class, Integer.class, long.class, Long.class,
            double.class, Double.class, float.class, Float.class
    ));

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

    String buildParameterTypeDigest(TestCluster cluster) {
        if (cluster == null) {
            return "";
        }
        List<GenericAccessibleObject<?>> testCalls = cluster.getTestCalls();
        if (testCalls == null || testCalls.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (GenericAccessibleObject<?> call : testCalls) {
            if (!call.isMethod() || !(call.getAccessibleObject() instanceof Method)) {
                continue;
            }
            Method method = (Method) call.getAccessibleObject();
            Class<?>[] paramTypes = method.getParameterTypes();
            boolean hasConstantParam = false;
            for (Class<?> paramType : paramTypes) {
                if (CONSTANT_COMPATIBLE_TYPES.contains(paramType)) {
                    hasConstantParam = true;
                    break;
                }
            }
            if (!hasConstantParam) {
                continue;
            }

            StringBuilder paramList = new StringBuilder();
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) {
                    paramList.append(", ");
                }
                paramList.append(paramTypes[i].getSimpleName());
            }
            String line = "  " + method.getName() + "(" + paramList + ")\n";
            if (sb.length() + line.length() > 1000) {
                break;
            }
            sb.append(line);
        }

        if (sb.length() == 0) {
            return "";
        }
        return "\nCUT methods that accept constant-compatible parameters:\n" + sb.toString();
    }

    PromptResult buildPrompt(String className, TestCluster cluster) {
        String paramDigest = buildParameterTypeDigest(cluster);

        PromptBuilder builder = new PromptBuilder();
        builder.withSystemPrompt()
                .withSutContext(className, cluster)
                .withInstruction(
                        "For testing the class " + className + ", suggest useful constant values that would exercise "
                        + "edge cases, boundary conditions, and interesting code paths.\n\n"
                        + "Provide constants as a list of typed literals. Include:\n"
                        + "- Strings: edge-case strings (empty, whitespace, special chars, long, Unicode, SQL, paths)\n"
                        + "- Integers: boundary values (0, -1, 1, Integer.MAX_VALUE, Integer.MIN_VALUE, powers of 2)\n"
                        + "- Longs: boundary values with L suffix (0L, -1L, Long.MAX_VALUE)\n"
                        + "- Doubles: boundary values (0.0, -0.0, 1.0, Double.MAX_VALUE, NaN, Infinity)\n"
                        + "- Floats: boundary values with f suffix (0.0f, 1.0f, Float.MAX_VALUE)\n\n"
                        + "Format each constant on its own line as a Java literal. Example:\n"
                        + "\"\" \n"
                        + "\"hello world\"\n"
                        + "0\n"
                        + "-1\n"
                        + "2147483647\n"
                        + "0L\n"
                        + "3.14\n"
                        + "1.0f\n"
                        + paramDigest + "\n"
                        + "Only provide the literal values, no explanations needed.")
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE);
        return builder.buildWithMetadata();
    }

    PromptResult buildDependencyPrompt(String sutClassName, String dependencyClassName, TestCluster cluster) {
        PromptBuilder builder = new PromptBuilder();
        builder.withSystemPrompt()
                .withSutContext(sutClassName, cluster)
                .withInstruction(
                        "For testing class " + sutClassName + ", suggest useful constant values specifically for "
                        + "interactions with dependency class " + dependencyClassName + ".\n\n"
                        + "Focus on dependency-facing values (ids, keys, paths, protocol tokens, numeric boundaries).\n"
                        + "Provide typed Java literals only, one per line.\n"
                        + "Do not include explanations.")
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
                    // Exclude JDK types — querying the LLM for constants
                    // specific to HashMap or File is high-cost, low-signal.
                    if (LlmObjectPoolEnricher.isJdkType(name)) {
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
     * Parses typed constants from LLM response text. Delegates to
     * {@link ConstantResponseParser#parseConstants(String)} — retained as a
     * static method on the enricher for binary compatibility with existing
     * tests and call sites.
     */
    static List<Object> parseConstants(String response) {
        return ConstantResponseParser.parseConstants(response);
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

    /**
     * Unescapes a Java string literal body. Delegates to
     * {@link ConstantResponseParser#unescapeJavaString(String)} — retained
     * here for test/binary compatibility.
     */
    static String unescapeJavaString(String escaped) {
        return ConstantResponseParser.unescapeJavaString(escaped);
    }

    /**
     * Result of an enrichment attempt.
     */
    public static class EnrichmentResult extends AbstractLlmEnricher.EnrichmentResult {
        private final int sutConstantsAdded;
        private final int nonSutConstantsAdded;
        private final int constantsParsed;

        /** Creates an enrichment result with counts of SUT/non-SUT constants added and an optional failure reason. */
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

        @Override
        public String summarize(String label) {
            return String.format(
                    "%s enrichment: attempted=%s, parsed=%d, sutAdded=%d, nonSutAdded=%d%s",
                    label, isAttempted(), constantsParsed, sutConstantsAdded, nonSutConstantsAdded,
                    getFailureReason() != null ? ", failure=" + getFailureReason() : "");
        }

        @Override
        public void trackMetrics() {
            try {
                ClientServices.track(RuntimeVariable.LLM_Constants_Added_SUT, sutConstantsAdded);
                ClientServices.track(RuntimeVariable.LLM_Constants_Added_NonSUT, nonSutConstantsAdded);
            } catch (Throwable t) {
                // ClientServices may be unavailable in unit tests — best-effort tracking
            }
        }
    }
}
