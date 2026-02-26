package org.evosuite.llm.seeding;

import org.evosuite.Properties;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.ClusterExpansionManager;
import org.evosuite.llm.response.LlmResponseParser;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.seeding.ObjectPoolManager;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Enriches EvoSuite's object pool with LLM-generated construction sequences.
 * Designed for async invocation with graceful failure handling.
 */
public class LlmObjectPoolEnricher {

    private static final Logger logger = LoggerFactory.getLogger(LlmObjectPoolEnricher.class);

    /** Max type keys per sequence to bound pool pollution. */
    static final int MAX_KEYS_PER_SEQUENCE = 5;

    private static final String[] JDK_PACKAGE_PREFIXES = {
            "java.", "javax.", "sun.", "com.sun.", "jdk."
    };

    private final LlmService llmService;
    private final TestRepairLoop repairLoop;

    public LlmObjectPoolEnricher(LlmService llmService, TestRepairLoop repairLoop) {
        this.llmService = llmService;
        this.repairLoop = repairLoop;
    }

    /**
     * Creates an enricher with default repair loop configuration.
     */
    public LlmObjectPoolEnricher(LlmService llmService) {
        this(llmService, createDefaultRepairLoop(llmService));
    }

    /**
     * Asynchronously queries the LLM for construction sequences and adds them to the object pool.
     */
    public CompletableFuture<EnrichmentResult> enrichAsync(String className, TestCluster cluster) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return enrich(className, cluster);
            } catch (Throwable t) {
                logger.warn("Object pool enrichment failed: {}", t.getMessage());
                return EnrichmentResult.failure(t.getMessage());
            }
        });
    }

    EnrichmentResult enrich(String className, TestCluster cluster) {
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            logger.debug("LLM not available or no budget for object pool enrichment");
            return EnrichmentResult.skipped("LLM unavailable or no budget");
        }

        // Identify complex types from the test cluster that would benefit from construction sequences
        List<String> typeNames = collectInterestingTypes(cluster);
        if (typeNames.isEmpty()) {
            logger.debug("No complex types found for object pool enrichment");
            return EnrichmentResult.skipped("No complex types found");
        }

        PromptResult promptResult = buildPrompt(className, cluster, typeNames);
        String response;
        try {
            response = llmService.query(promptResult, LlmFeature.OBJECT_POOL_ENRICHMENT);
        } catch (Throwable t) {
            logger.warn("LLM query failed for object pool enrichment: {}", t.getMessage());
            return EnrichmentResult.failure("LLM query failed: " + t.getMessage());
        }

        return parseAndAddSequences(response, promptResult, className,
                new LinkedHashSet<>(typeNames));
    }

    private EnrichmentResult parseAndAddSequences(String response, PromptResult promptResult,
                                                    String className, Set<String> targetTypeNames) {
        int sequencesParsed = 0;
        int insertionsAccepted = 0;
        int rejectedNoType = 0;
        int rejectedValidation = 0;
        int rejectedAddFailure = 0;
        List<String> diagnostics = new ArrayList<>();

        try {
            RepairResult repairResult = repairLoop.attemptParse(
                    response, promptResult.getMessages(), LlmFeature.OBJECT_POOL_ENRICHMENT);

            if (repairResult.isSuccess() && repairResult.getParseResults() != null) {
                for (ParseResult parseResult : repairResult.getParseResults()) {
                    if (parseResult.getTestCase() != null && parseResult.getTestCase().size() > 0) {
                        sequencesParsed++;
                        TypeKeyInsertionResult insertionResult =
                                addSequenceToPoolByProducedTypes(parseResult.getTestCase(), targetTypeNames);
                        insertionsAccepted += insertionResult.insertions;
                        rejectedNoType += insertionResult.rejectedNoType;
                        rejectedValidation += insertionResult.rejectedValidation;
                        rejectedAddFailure += insertionResult.rejectedAddFailure;
                        diagnostics.addAll(insertionResult.diagnostics);
                    }
                }
            } else {
                diagnostics.add("Repair loop did not produce successful results");
                if (repairResult.getDiagnostics() != null) {
                    diagnostics.addAll(repairResult.getDiagnostics());
                }
            }
        } catch (Throwable t) {
            logger.warn("Failed to parse object pool sequences: {}", t.getMessage());
            diagnostics.add("Parse failure: " + t.getMessage());
        }

        logger.info("Object pool enrichment: parsed={}, insertions={}, rejectedNoType={}, " +
                        "rejectedValidation={}, rejectedAddFailure={}",
                sequencesParsed, insertionsAccepted, rejectedNoType, rejectedValidation, rejectedAddFailure);
        if (!diagnostics.isEmpty()) {
            logger.debug("Object pool enrichment diagnostics: {}", diagnostics);
        }

        return new EnrichmentResult(true, insertionsAccepted, sequencesParsed,
                rejectedNoType, rejectedValidation, rejectedAddFailure,
                insertionsAccepted > 0 ? null : "No sequences added" +
                        (diagnostics.isEmpty() ? "" : ": " + diagnostics));
    }

    /**
     * Policy B-tight: Post-parse produced-type discovery with JDK filtering.
     * <p>
     * Discovers non-trivial types produced by statements, filters to domain-relevant
     * candidates (non-JDK or explicitly targeted), validates each with both
     * {@code hasObject} and {@code getLastObject}, then inserts under valid type keys.
     * <p>
     * Type-key filtering policy:
     * <ol>
     *   <li>Basic exclusions: primitives, wrappers, void, Object</li>
     *   <li>JDK types (java.*, javax.*, sun.*, com.sun.*, jdk.*) excluded
     *       unless present in {@code targetTypeNames}</li>
     *   <li>Capped at {@link #MAX_KEYS_PER_SEQUENCE} to bound pool pollution</li>
     *   <li>Both {@code hasObject(T, size())} and {@code getLastObject(T)} must succeed</li>
     * </ol>
     * This avoids polluting the pool with incidental helper types while preserving
     * Phase 3c's intent of keying by the actual produced type, not always the CUT.
     */
    TypeKeyInsertionResult addSequenceToPoolByProducedTypes(TestCase testCase,
                                                            Set<String> targetTypeNames) {
        int insertions = 0;
        int rejectedNoType = 0;
        int rejectedValidation = 0;
        int rejectedAddFailure = 0;
        List<String> diagnostics = new ArrayList<>();

        Set<Class<?>> rawTypes = discoverProducedTypes(testCase);
        if (rawTypes.isEmpty()) {
            rejectedNoType++;
            diagnostics.add("No non-trivial types produced by sequence");
            return new TypeKeyInsertionResult(insertions, rejectedNoType,
                    rejectedValidation, rejectedAddFailure, diagnostics);
        }

        Set<Class<?>> candidateTypes = filterCandidateKeyTypes(rawTypes, targetTypeNames);
        if (candidateTypes.isEmpty()) {
            rejectedNoType++;
            diagnostics.add("All produced types filtered out (JDK helpers, not in target set)");
            return new TypeKeyInsertionResult(insertions, rejectedNoType,
                    rejectedValidation, rejectedAddFailure, diagnostics);
        }

        for (Class<?> type : candidateTypes) {
            // Validate with hasObject — mirrors ObjectPool.getPoolFromTestSuite check
            if (!testCase.hasObject(type, testCase.size())) {
                rejectedValidation++;
                diagnostics.add("hasObject failed for type: " + type.getName());
                continue;
            }

            // Validate with getLastObject — mirrors ObjectPool retrieval path
            try {
                testCase.getLastObject(type);
            } catch (ConstructionFailedException e) {
                rejectedValidation++;
                diagnostics.add("getLastObject failed for type: " + type.getName());
                continue;
            }

            try {
                GenericClass<?> genericClass = GenericClassFactory.get(type);
                ObjectPoolManager.getInstance().addSequence(genericClass, testCase);
                insertions++;
                logger.debug("Inserted sequence under type key: {}", type.getName());
            } catch (Throwable t) {
                rejectedAddFailure++;
                diagnostics.add("Add failure for " + type.getName() + ": " + t.getMessage());
            }
        }

        return new TypeKeyInsertionResult(insertions, rejectedNoType,
                rejectedValidation, rejectedAddFailure, diagnostics);
    }

    /**
     * Discovers all non-trivial types produced by statements in a test case.
     * Skips primitives, primitive wrappers, void, and Object.
     * Returns types in deterministic (statement) order.
     */
    Set<Class<?>> discoverProducedTypes(TestCase testCase) {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (int i = 0; i < testCase.size(); i++) {
            Statement st = testCase.getStatement(i);
            if (st == null) {
                continue;
            }
            VariableReference retVal = st.getReturnValue();
            if (retVal == null) {
                continue;
            }

            Class<?> varClass = retVal.getVariableClass();
            if (varClass == null || varClass == void.class || varClass == Void.class) {
                continue;
            }
            if (varClass.isPrimitive() || retVal.isWrapperType()) {
                continue;
            }
            if (varClass == Object.class) {
                continue;
            }

            types.add(varClass);
        }
        return types;
    }

    /**
     * Filters raw produced types to candidate pool keys.
     * Keeps non-JDK domain types unconditionally; keeps JDK types only if
     * they appear in the enrichment target set. Capped at {@link #MAX_KEYS_PER_SEQUENCE}.
     */
    Set<Class<?>> filterCandidateKeyTypes(Set<Class<?>> rawTypes, Set<String> targetTypeNames) {
        Set<String> targets = targetTypeNames != null ? targetTypeNames : Collections.emptySet();
        Set<Class<?>> filtered = new LinkedHashSet<>();
        for (Class<?> type : rawTypes) {
            if (filtered.size() >= MAX_KEYS_PER_SEQUENCE) {
                break;
            }
            String name = type.getName();
            if (!isJdkType(name) || targets.contains(name)) {
                filtered.add(type);
            }
        }
        return filtered;
    }

    static boolean isJdkType(String className) {
        for (String prefix : JDK_PACKAGE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    List<String> collectInterestingTypes(TestCluster cluster) {
        List<String> types = new ArrayList<>();
        try {
            Set<Class<?>> analyzedClasses = cluster.getAnalyzedClasses();
            if (analyzedClasses != null) {
                for (Class<?> clazz : analyzedClasses) {
                    if (clazz != null && !clazz.isPrimitive() && !clazz.isArray()
                            && !clazz.getName().startsWith("java.lang.")
                            && !clazz.isEnum()) {
                        types.add(clazz.getName());
                    }
                }
            }
        } catch (Throwable t) {
            logger.debug("Failed to collect interesting types: {}", t.getMessage());
        }
        return types;
    }

    PromptResult buildPrompt(String className, TestCluster cluster, List<String> typeNames) {
        StringBuilder typeList = new StringBuilder();
        int limit = Math.min(typeNames.size(), 10);
        for (int i = 0; i < limit; i++) {
            typeList.append("- ").append(typeNames.get(i)).append("\n");
        }

        PromptBuilder builder = new PromptBuilder();
        builder.withSystemPrompt()
                .withSutContext(className, cluster)
                .withInstruction(
                        "Generate Java test methods that construct objects useful for testing " + className + ".\n\n" +
                        "Key types involved:\n" + typeList + "\n" +
                        "For each type, create a @Test method that:\n" +
                        "1. Constructs an instance with interesting state\n" +
                        "2. Exercises constructors, setters, and builder patterns\n" +
                        "3. Creates edge-case configurations (empty, null fields, boundary values)\n\n" +
                        "Format as a complete JUnit test class with imports.\n" +
                        "Each method should set up one interesting object state.\n" +
                        "Do NOT include assertions - focus on object construction only.")
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE);
        return builder.buildWithMetadata();
    }

    private static TestRepairLoop createDefaultRepairLoop(LlmService llmService) {
        TestParser testParser = TestParser.forSUT();
        LlmResponseParser responseParser = new LlmResponseParser();
        ClusterExpansionManager expansionManager = new ClusterExpansionManager();
        return new TestRepairLoop(llmService, testParser, responseParser, expansionManager);
    }

    /**
     * Result of an object pool enrichment attempt.
     */
    public static class EnrichmentResult {
        private final boolean attempted;
        private final int sequencesAdded;
        private final int sequencesParsed;
        private final int rejectedNoType;
        private final int rejectedValidation;
        private final int rejectedAddFailure;
        private final String failureReason;

        public EnrichmentResult(boolean attempted, int sequencesAdded, int sequencesParsed,
                                int rejectedNoType, int rejectedValidation, int rejectedAddFailure,
                                String failureReason) {
            this.attempted = attempted;
            this.sequencesAdded = sequencesAdded;
            this.sequencesParsed = sequencesParsed;
            this.rejectedNoType = rejectedNoType;
            this.rejectedValidation = rejectedValidation;
            this.rejectedAddFailure = rejectedAddFailure;
            this.failureReason = failureReason;
        }

        static EnrichmentResult skipped(String reason) {
            return new EnrichmentResult(false, 0, 0, 0, 0, 0, reason);
        }

        static EnrichmentResult failure(String reason) {
            return new EnrichmentResult(true, 0, 0, 0, 0, 0, reason);
        }

        public boolean isAttempted() {
            return attempted;
        }

        public int getSequencesAdded() {
            return sequencesAdded;
        }

        public int getSequencesParsed() {
            return sequencesParsed;
        }

        public int getRejectedNoType() {
            return rejectedNoType;
        }

        public int getRejectedValidation() {
            return rejectedValidation;
        }

        public int getRejectedAddFailure() {
            return rejectedAddFailure;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public boolean isSuccess() {
            return attempted && failureReason == null;
        }
    }

    /**
     * Internal result of attempting to insert a single sequence under its produced type keys.
     */
    static class TypeKeyInsertionResult {
        final int insertions;
        final int rejectedNoType;
        final int rejectedValidation;
        final int rejectedAddFailure;
        final List<String> diagnostics;

        TypeKeyInsertionResult(int insertions, int rejectedNoType,
                               int rejectedValidation, int rejectedAddFailure,
                               List<String> diagnostics) {
            this.insertions = insertions;
            this.rejectedNoType = rejectedNoType;
            this.rejectedValidation = rejectedValidation;
            this.rejectedAddFailure = rejectedAddFailure;
            this.diagnostics = diagnostics;
        }
    }
}
