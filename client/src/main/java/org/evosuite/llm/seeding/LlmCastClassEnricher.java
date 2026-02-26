package org.evosuite.llm.seeding;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.seeding.CastClassManager;
import org.evosuite.setup.TestCluster;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.utils.generic.GenericClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches CastClassManager with LLM-suggested cast-relevant class names.
 * Designed for synchronous invocation within the cast-class setup path.
 * All failures are logged and swallowed — never crashes startup.
 */
public class LlmCastClassEnricher {

    private static final Logger logger = LoggerFactory.getLogger(LlmCastClassEnricher.class);

    /**
     * Matches a JSON object containing a "suggestions" array of strings.
     * Tolerant of whitespace and optional trailing commas.
     */
    static final Pattern JSON_SUGGESTIONS_PATTERN = Pattern.compile(
            "\"suggestions\"\\s*:\\s*\\[([^\\]]*)]", Pattern.DOTALL);

    /** Matches a JSON string value inside an array. */
    static final Pattern JSON_STRING_ELEMENT = Pattern.compile("\"([^\"]+)\"");

    /** Matches a bare JSON array of strings (fallback). */
    static final Pattern BARE_ARRAY_PATTERN = Pattern.compile(
            "^\\s*\\[([^\\]]*)]", Pattern.DOTALL | Pattern.MULTILINE);

    /**
     * Matches a fully-qualified class name on its own line (fallback).
     * Accepts standard Java package/class naming: letters, digits, underscores, dots, and $ for inner classes.
     */
    static final Pattern FQCN_LINE_PATTERN = Pattern.compile(
            "^\\s*([a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)+(\\$[a-zA-Z_$][a-zA-Z0-9_$]*)*)\\s*$",
            Pattern.MULTILINE);

    /** 
     * Weaker priority than analyzer-derived entries.
     * Analyzer-derived classes use actual cast counts (typically 1–10); lower values sort 
     * earlier in Prioritization (stronger). A value of 100 ensures LLM suggestions are 
     * clearly subordinate to bytecode-evidence entries.
     */
    static final int LLM_CAST_CLASS_PRIORITY = 100;

    private final LlmService llmService;

    public LlmCastClassEnricher(LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * Queries the LLM for cast-relevant class suggestions and adds validated ones to CastClassManager.
     * Never throws; all failures are logged.
     *
     * @param className  the target class under test
     * @param cluster    the test cluster for prompt context (may be null)
     * @return result with counts of suggested, validated, and accepted classes
     */
    public EnrichmentResult enrich(String className, TestCluster cluster) {
        try {
            return doEnrich(className, cluster);
        } catch (Throwable t) {
            logger.warn("Cast class enrichment failed (non-fatal): {}", t.getMessage());
            return EnrichmentResult.failure(t.getMessage());
        }
    }

    private EnrichmentResult doEnrich(String className, TestCluster cluster) {
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            logger.debug("LLM not available or no budget for cast class enrichment");
            return EnrichmentResult.skipped("LLM unavailable or no budget");
        }

        PromptResult promptResult = buildPrompt(className, cluster);
        String response;
        try {
            response = llmService.query(promptResult, LlmFeature.CAST_CLASS_ENRICHMENT);
        } catch (Throwable t) {
            logger.warn("LLM query failed for cast class enrichment: {}", t.getMessage());
            return EnrichmentResult.failure("LLM query failed: " + t.getMessage());
        }

        List<String> suggestions = parseSuggestions(response);
        if (suggestions.isEmpty()) {
            logger.debug("No cast class suggestions parsed from LLM response");
            return new EnrichmentResult(true, 0, 0, 0, "No suggestions parsed");
        }

        return validateAndAdd(suggestions, className);
    }

    /**
     * Parses class name suggestions from the LLM response.
     * Tries (in order): JSON object with "suggestions" key, bare JSON array, line-by-line FQCNs.
     */
    static List<String> parseSuggestions(String response) {
        if (response == null || response.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // Strategy 1: JSON object with "suggestions" key
        List<String> result = parseJsonSuggestionsObject(response);
        if (!result.isEmpty()) {
            return result;
        }

        // Strategy 2: Bare JSON array
        result = parseBareJsonArray(response);
        if (!result.isEmpty()) {
            return result;
        }

        // Strategy 3: Line-based FQCN extraction
        return parseLineBasedFqcns(response);
    }

    private static List<String> parseJsonSuggestionsObject(String response) {
        Matcher matcher = JSON_SUGGESTIONS_PATTERN.matcher(response);
        if (!matcher.find()) {
            return Collections.emptyList();
        }
        String arrayContent = matcher.group(1);
        return extractStringsFromJsonArray(arrayContent);
    }

    private static List<String> parseBareJsonArray(String response) {
        Matcher matcher = BARE_ARRAY_PATTERN.matcher(response);
        if (!matcher.find()) {
            return Collections.emptyList();
        }
        String arrayContent = matcher.group(1);
        return extractStringsFromJsonArray(arrayContent);
    }

    private static List<String> extractStringsFromJsonArray(String arrayContent) {
        List<String> result = new ArrayList<>();
        Matcher stringMatcher = JSON_STRING_ELEMENT.matcher(arrayContent);
        while (stringMatcher.find()) {
            String value = stringMatcher.group(1).trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static List<String> parseLineBasedFqcns(String response) {
        List<String> result = new ArrayList<>();
        Matcher matcher = FQCN_LINE_PATTERN.matcher(response);
        while (matcher.find()) {
            String fqcn = matcher.group(1).trim();
            if (!fqcn.isEmpty()) {
                result.add(fqcn);
            }
        }
        return result;
    }

    EnrichmentResult validateAndAdd(List<String> suggestions, String className) {
        int suggested = suggestions.size();
        int validated = 0;
        int classesAdded = 0;
        int cap = Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS;

        // Collect existing cast classes for deduplication
        Set<String> existingClassNames = new LinkedHashSet<>();
        for (GenericClass<?> gc : CastClassManager.getInstance().getCastClasses()) {
            existingClassNames.add(gc.getRawClass().getName());
        }

        Set<String> seenNames = new LinkedHashSet<>();
        ClassLoader sutLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();

        for (String suggestion : suggestions) {
            if (classesAdded >= cap) {
                break;
            }

            // Deterministic deduplication: skip if already seen in this batch
            if (!seenNames.add(suggestion)) {
                logger.debug("Cast class enrichment: skipping duplicate suggestion '{}'", suggestion);
                continue;
            }

            // Skip if already in CastClassManager
            if (existingClassNames.contains(suggestion)) {
                logger.debug("Cast class enrichment: '{}' already in CastClassManager", suggestion);
                continue;
            }

            // Validate: loadable from SUT classloader
            Class<?> clazz;
            try {
                clazz = sutLoader.loadClass(suggestion);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                logger.debug("Cast class enrichment: '{}' not loadable: {}", suggestion, e.getMessage());
                continue;
            }

            // Validate: not primitive, not array
            if (clazz.isPrimitive() || clazz.isArray()) {
                logger.debug("Cast class enrichment: '{}' is primitive or array, skipping", suggestion);
                continue;
            }

            // Validate: TestUsageChecker.canUse
            if (!TestUsageChecker.canUse(clazz)) {
                logger.debug("Cast class enrichment: '{}' cannot be used per TestUsageChecker", suggestion);
                continue;
            }

            validated++;

            // Snapshot size before add to measure actual classes added
            int sizeBefore = CastClassManager.getInstance().getCastClasses().size();

            try {
                CastClassManager.getInstance().addCastClass(suggestion, LLM_CAST_CLASS_PRIORITY);
            } catch (Throwable t) {
                logger.debug("Cast class enrichment: failed to add '{}': {}", suggestion, t.getMessage());
                continue;
            }

            int added = CastClassManager.getInstance().getCastClasses().size() - sizeBefore;
            classesAdded += added;
            if (added > 0) {
                logger.debug("Cast class enrichment: accepted '{}' ({} class(es) added)", suggestion, added);
            }
        }

        logger.info("Cast class enrichment for {}: suggested={}, validated={}, classesAdded={} (cap={})",
                className, suggested, validated, classesAdded, cap);

        String failureReason = classesAdded > 0 ? null
                : "No classes added (suggested=" + suggested + ", validated=" + validated + ")";
        return new EnrichmentResult(true, suggested, validated, classesAdded, failureReason);
    }

    PromptResult buildPrompt(String className, TestCluster cluster) {
        PromptBuilder builder = new PromptBuilder();
        builder.withSystemPrompt()
                .withSutContext(className, cluster)
                .withInstruction(
                        "For the Java class " + className + ", suggest additional concrete classes that would be " +
                        "useful as cast targets when generating tests.\n\n" +
                        "Think about:\n" +
                        "- Concrete implementations of interfaces/abstract classes used by " + className + "\n" +
                        "- Subclasses that appear in instanceof checks or type casts\n" +
                        "- Common collection types, wrapper types, or domain types relevant to the API\n\n" +
                        "Return your answer as a JSON object with a single key \"suggestions\" containing " +
                        "an array of fully-qualified Java class names (strings).\n\n" +
                        "Example:\n" +
                        "{\"suggestions\": [\"java.util.HashMap\", \"java.util.TreeSet\", \"java.io.File\"]}\n\n" +
                        "Rules:\n" +
                        "- Only include concrete, instantiable classes (no interfaces or abstract classes " +
                        "unless they can be mocked)\n" +
                        "- Use fully-qualified class names\n" +
                        "- Suggest at most " + Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS + " classes\n" +
                        "- Return ONLY the JSON object, no explanations")
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE);
        return builder.buildWithMetadata();
    }

    /**
     * Checks whether cast class enrichment should run.
     */
    public static boolean isEnabled() {
        return Properties.LLM_ENRICH_CAST_CLASSES
                && Properties.LLM_PROVIDER != Properties.LlmProvider.NONE;
    }

    /**
     * Result of a cast class enrichment attempt.
     */
    public static class EnrichmentResult {
        private final boolean attempted;
        private final int suggested;
        private final int validated;
        private final int accepted;
        private final String failureReason;

        public EnrichmentResult(boolean attempted, int suggested, int validated, int accepted, String failureReason) {
            this.attempted = attempted;
            this.suggested = suggested;
            this.validated = validated;
            this.accepted = accepted;
            this.failureReason = failureReason;
        }

        static EnrichmentResult skipped(String reason) {
            return new EnrichmentResult(false, 0, 0, 0, reason);
        }

        static EnrichmentResult failure(String reason) {
            return new EnrichmentResult(true, 0, 0, 0, reason);
        }

        public boolean isAttempted() {
            return attempted;
        }

        public int getSuggested() {
            return suggested;
        }

        public int getValidated() {
            return validated;
        }

        public int getAccepted() {
            return accepted;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public boolean isSuccess() {
            return attempted && failureReason == null;
        }
    }
}
