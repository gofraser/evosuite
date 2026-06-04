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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.seeding.CastClassManager;
import org.evosuite.setup.TestCluster;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.utils.generic.GenericClass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches CastClassManager with LLM-suggested cast-relevant class names.
 * Designed for synchronous invocation within the cast-class setup path.
 * All failures are logged and swallowed — never crashes startup.
 */
public class LlmCastClassEnricher extends AbstractLlmEnricher<LlmCastClassEnricher.EnrichmentResult> {

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

    public LlmCastClassEnricher(LlmService llmService) {
        super(llmService, LlmFeature.CAST_CLASS_ENRICHMENT);
    }

    /**
     * Synchronous enrichment logic (called by base class async template).
     */
    @Override
    protected EnrichmentResult doEnrich(String className, TestCluster cluster) {
        PromptResult promptResult = buildPrompt(className, cluster);
        String response = llmService.query(promptResult, feature);

        List<String> suggestions = parseSuggestions(response);
        if (suggestions.isEmpty()) {
            logger.debug("No cast class suggestions parsed from LLM response");
            return new EnrichmentResult(true, 0, 0, 0, "No suggestions parsed");
        }

        return validateAndAdd(suggestions, className);
    }

    @Override
    protected EnrichmentResult createSkippedResult(String reason) {
        return EnrichmentResult.skipped(reason);
    }

    @Override
    protected EnrichmentResult createFailureResult(String reason) {
        return EnrichmentResult.failure(reason);
    }

    /**
     * Legacy synchronous entry-point for blocking setup paths.
     * Delegates to doEnrich but preserves original error swallowing.
     */
    public EnrichmentResult enrich(String className, TestCluster cluster) {
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            return createSkippedResult("LLM unavailable or no budget");
        }
        try {
            return doEnrich(className, cluster);
        } catch (Throwable t) {
            logger.warn("Cast class enrichment failed (non-fatal): {}", t.getMessage());
            return createFailureResult(t.getMessage());
        }
    }

    /** Reusable Jackson mapper — thread-safe per Jackson docs. */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * Parses class name suggestions from the LLM response.
     * Tries (in order): proper JSON via Jackson (handles object-with-key or
     * bare array), regex JSON-shape fallbacks (for responses with prose
     * around the JSON), line-by-line FQCNs.
     */
    static List<String> parseSuggestions(String response) {
        if (response == null || response.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // Strategy 0: Strict JSON parse via Jackson. Handles nested brackets
        // inside strings, escape sequences, and unicode that the regex paths miss.
        List<String> result = parseStrictJson(response);
        if (!result.isEmpty()) {
            return result;
        }

        // Strategy 1: JSON object with "suggestions" key, extracted by regex
        // (for responses with prose wrapping the JSON object).
        result = parseJsonSuggestionsObject(response);
        if (!result.isEmpty()) {
            return result;
        }

        // Strategy 2: Bare JSON array, extracted by regex
        result = parseBareJsonArray(response);
        if (!result.isEmpty()) {
            return result;
        }

        // Strategy 3: Line-based FQCN extraction
        return parseLineBasedFqcns(response);
    }

    /**
     * Attempts a strict JSON parse of the trimmed response. Accepts either a
     * top-level object with a {@code "suggestions"} array, or a top-level
     * array of strings. Returns an empty list (without throwing) on any parse
     * failure so the caller falls through to the regex strategies.
     */
    private static List<String> parseStrictJson(String response) {
        String trimmed = response.trim();
        // Cheap guard: don't even ask Jackson unless this looks like JSON.
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = JSON_MAPPER.readTree(trimmed);
            JsonNode arrayNode;
            if (root.isObject() && root.hasNonNull("suggestions")) {
                arrayNode = root.get("suggestions");
            } else if (root.isArray()) {
                arrayNode = root;
            } else {
                return Collections.emptyList();
            }
            if (!arrayNode.isArray()) {
                return Collections.emptyList();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode element : arrayNode) {
                if (element != null && element.isTextual()) {
                    String value = element.asText().trim();
                    if (!value.isEmpty()) {
                        values.add(value);
                    }
                }
            }
            return values;
        } catch (Exception e) {
            return Collections.emptyList();
        }
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
        LlmStatistics.recordCastClassSuggestions(suggested);
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
            if (classesAdded >= cap || isCancelled()) {
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

            // Validate: TestUsageChecker.canUse — only for concrete classes. For
            // abstract classes and interfaces we let CastClassManager.addCastClass
            // expand them to concrete subclasses and validate each subclass there;
            // skipping here would drop e.g. java.util.List → ArrayList/LinkedList.
            if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers())
                    && !TestUsageChecker.canUse(clazz)) {
                logger.debug("Cast class enrichment: '{}' cannot be used per TestUsageChecker", suggestion);
                continue;
            }

            validated++;

            // Final cancellation check before mutating CastClassManager
            if (isCancelled()) {
                logger.debug("Cast class enrichment: cancelled before adding '{}'", suggestion);
                break;
            }

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
                LlmStatistics.recordCastClassesAccepted(added);
                logger.debug("Cast class enrichment: accepted '{}' ({} class(es) added)", suggestion, added);
            }

            // A single abstract suggestion may expand to multiple concrete classes,
            // potentially overshooting the cap. Stop immediately if exceeded.
            if (classesAdded >= cap) {
                break;
            }
        }

        logger.info("Cast class enrichment for {}: suggested={}, validated={}, classesAdded={} (cap={})",
                className, suggested, validated, classesAdded, cap);

        String failureReason = classesAdded > 0 ? null
                : "No classes added (suggested=" + suggested + ", validated=" + validated + ")";
        return new EnrichmentResult(true, suggested, validated, classesAdded, failureReason);
    }

    private static final Set<String> TOO_GENERIC_TYPES = new HashSet<>(Arrays.asList(
            "java.lang.Object", "java.io.Serializable", "java.lang.Comparable"
    ));

    String buildAbstractTypeContext(String className) {
        if (className == null) {
            return "";
        }
        Class<?> cutClass;
        try {
            cutClass = Class.forName(className, false,
                    Thread.currentThread().getContextClassLoader());
        } catch (Throwable t) {
            return "";
        }

        // Collect interface/abstract types from CUT API
        Set<Class<?>> abstractTypes = new LinkedHashSet<>();
        try {
            for (Constructor<?> ctor : cutClass.getConstructors()) {
                for (Class<?> paramType : ctor.getParameterTypes()) {
                    if (isAbstractOrInterface(paramType)) {
                        abstractTypes.add(paramType);
                    }
                }
            }
            for (Method method : cutClass.getMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())
                        || method.getDeclaringClass() == Object.class) {
                    continue;
                }
                for (Class<?> paramType : method.getParameterTypes()) {
                    if (isAbstractOrInterface(paramType)) {
                        abstractTypes.add(paramType);
                    }
                }
                Class<?> returnType = method.getReturnType();
                if (isAbstractOrInterface(returnType)) {
                    abstractTypes.add(returnType);
                }
            }
        } catch (Throwable t) {
            logger.debug("Error inspecting CUT methods for abstract types: {}", t.getMessage());
        }

        if (abstractTypes.isEmpty()) {
            return "";
        }

        // Check existing cast classes for known implementations
        Set<GenericClass<?>> castClasses = CastClassManager.getInstance().getCastClasses();

        StringBuilder sb = new StringBuilder("\nInterfaces/abstract classes used in the SUT API:\n");
        for (Class<?> abstractType : abstractTypes) {
            List<String> knownImpls = new ArrayList<>();
            for (GenericClass<?> gc : castClasses) {
                try {
                    Class<?> castRaw = gc.getRawClass();
                    if (castRaw != null && abstractType.isAssignableFrom(castRaw)
                            && castRaw != abstractType) {
                        knownImpls.add(castRaw.getSimpleName());
                    }
                } catch (Throwable t) {
                    // skip
                }
            }

            String implInfo = knownImpls.isEmpty()
                    ? "(no concrete implementations registered)"
                    : "(already have: " + String.join(", ", knownImpls) + ")";
            String line = "- " + abstractType.getName() + " " + implInfo + "\n";
            if (sb.length() + line.length() > 1500) {
                break;
            }
            sb.append(line);
        }

        return sb.toString();
    }

    private static boolean isAbstractOrInterface(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isArray()) {
            return false;
        }
        if (TOO_GENERIC_TYPES.contains(type.getName())) {
            return false;
        }
        return type.isInterface() || Modifier.isAbstract(type.getModifiers());
    }

    String buildExistingCastContext() {
        Set<GenericClass<?>> castClasses = CastClassManager.getInstance().getCastClasses();
        if (castClasses == null || castClasses.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("Already registered cast classes: ");
        boolean first = true;
        for (GenericClass<?> gc : castClasses) {
            if (gc.getRawClass() == null) {
                continue;
            }
            String name = gc.getRawClass().getSimpleName();
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(name);
            if (sb.length() > 500) {
                sb.append(", ...");
                break;
            }
        }
        return sb.toString() + "\n";
    }

    PromptResult buildPrompt(String className, TestCluster cluster) {
        String abstractTypeContext = buildAbstractTypeContext(className);
        String existingCastContext = buildExistingCastContext();

        PromptBuilder builder = new PromptBuilder();
        builder.withSystemPrompt()
                .withSutContext(className, cluster)
                .withInstruction(
                        "For the Java class " + className + ", suggest additional concrete classes that would be "
                        + "useful as cast targets when generating tests.\n\n"
                        + "Think about:\n"
                        + "- Concrete implementations of interfaces/abstract classes used by " + className + "\n"
                        + "- Subclasses that appear in instanceof checks or type casts\n"
                        + "- Common collection types, wrapper types, or domain types relevant to the API\n"
                        + abstractTypeContext
                        + existingCastContext + "\n"
                        + "Return your answer as a JSON object with a single key \"suggestions\" containing "
                        + "an array of fully-qualified Java class names (strings).\n\n"
                        + "Example:\n"
                        + "{\"suggestions\": [\"java.util.HashMap\", \"java.util.TreeSet\", \"java.io.File\"]}\n\n"
                        + "Rules:\n"
                        + "- Only include concrete, instantiable classes (no interfaces or abstract classes "
                        + "unless they can be mocked)\n"
                        + "- Use fully-qualified class names\n"
                        + "- Suggest at most " + Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS + " classes\n"
                        + "- Return ONLY the JSON object, no explanations")
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
    public static class EnrichmentResult extends AbstractLlmEnricher.EnrichmentResult {
        private final int suggested;
        private final int validated;
        private final int accepted;

        /** Creates an enrichment result with attempt status, suggestion counts, and optional failure reason. */
        public EnrichmentResult(boolean attempted, int suggested, int validated, int accepted, String failureReason) {
            super(attempted, failureReason);
            this.suggested = suggested;
            this.validated = validated;
            this.accepted = accepted;
        }

        static EnrichmentResult skipped(String reason) {
            return new EnrichmentResult(false, 0, 0, 0, reason);
        }

        static EnrichmentResult failure(String reason) {
            return new EnrichmentResult(true, 0, 0, 0, reason);
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

        @Override
        public String summarize(String label) {
            return String.format(
                    "%s enrichment: attempted=%s, suggested=%d, validated=%d, added=%d%s",
                    label, isAttempted(), suggested, validated, accepted,
                    getFailureReason() != null ? ", failure=" + getFailureReason() : "");
        }

        @Override
        public void trackMetrics() {
            LlmStatistics.flushSeedingMetrics();
        }
    }
}
